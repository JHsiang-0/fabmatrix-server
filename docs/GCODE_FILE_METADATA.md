# G-code 文件信息与缩略图

## 采集入口

本地上传与设备文件读取统一为 `GCodeFileMetadata`。`GCodeMetadataService` 使用独立、有界后台队列处理文件，监控线程读取内存中的已确认结果。

- 本地上传成功并提交事务后，从持久化原文件解析。已有文件在本地任务需要时补解析。PNG 预览保存到现有本地文件存储，供文件页面使用。
- Klipper 先读 Moonraker 文件元数据与缩略图。RRF 先读 `rr_fileinfo` 与分页 `rr_thumbnail`，使用独立会话。需要补字段或层索引时，尝试有界、只读 G-code 下载解析。
- 设备文件读取前后核对版本，关联当前设备 UID、执行 UUID、文件名与已知大小。执行结束或设备连接信息在外部读取期间变化时，丢弃该结果。

文件访问只涉及读取和会话建立、释放。文件读取使用独立 HTTP 客户端，整体期限为30秒，失败后至少等待60秒再尝试。设备 G-code 下载上限为64MiB，本地解析上限为512MiB。较大文件仍可使用设备文件接口已提供的字段。

## 数据与来源

文件对象定义在 `src/main/java/com/example/farm/protocol/model/GCodeFileMetadata.java`，schemaVersion 为1。它提供文件身份、切片软件与版本、预计总时长、总层数、层高、首层高度、喷嘴直径、对象高度、普通与首层温度、最多8路耗材，以及最多2张缩略图描述。

未知字段为 null。设备接口和文件实际提供的内容决定字段覆盖。文件名对外使用经过清理的 basename，设备文件路径仅保存在现场缓存。

PNG、JPEG、QOI 缩略图经完整解码后统一输出 PNG，每张最多512KiB、宽高各不超过1024。哈希基于归一化后的 PNG 字节。没有预览或损坏的预览允许保留有效元数据，临时网络故障会重试。

实时执行仍使用 canonical modelVersion 2。

- 设备上报的层数、时间优先保留。
- 从切片文件补齐的预计总时长和总层数标记为 `SLICER_ESTIMATE`。
- 在完整文件层索引可用时，按已上报的文件字节位置估算当前层，标记为 `SERVER_DERIVED`。层索引最多100,000条，超限后整体舍弃。
- 缺少设备剩余时间时，可以用切片预计总时长减有效已耗时，标记为 `SERVER_MODEL`。超出预计时间后继续显示未知。

## 本地持久化

`LocalSchemaMigration` 在启动时幂等创建以下 SQLite 表，全新库的初始化 SQL 同样包含表定义。

| 表 | 用途 |
| --- | --- |
| farm_gcode_metadata | 上传文件或具体执行的元数据、内容摘要和层索引 |
| farm_gcode_thumbnail | 有界 PNG 字节，随元数据级联清理 |
| farm_gcode_cloud_receipt | 按网关、绑定代次、执行记录完整上传确认 |

上传文件删除会清理对应的私有解析缓存。缓存写入事务内重新检查文件存在性，避免后台解析与删除交错后重新留下缓存。现场保留独立数据和账号，云代理关闭时本地解析仍可使用。

## 云端传输与手机

实时观测继续走 MQTT schemaVersion 3。文件元数据与 PNG 使用独立、低优先级 HTTP 上传，共享现有带宽限制，实时状态消息优先。网关协商到 `FILE_METADATA_V1` 与 `FILE_THUMBNAIL_PNG_V1` 后才启用该上传。

`CloudFileMetadataSync` 每15秒尝试处理待上传记录，优先当前执行，并对历史记录分页。元数据和全部声明的 PNG 均成功后才写本地确认。失败、中断或重启后可以幂等重试。

Cloud 先完成 V24 升级，并接收 MQTT 执行记录，再接受该执行的文件资源。接口、授权、图片验证和限额详见同级 Cloud 仓库的 `docs/GCODE_FILE_METADATA.md`。

手机详情页读取对应执行的元数据与认证 PNG，分别显示切片预计总时长、实际或活动耗时、剩余时间、层数来源及文件工艺信息。旧 Cloud 返回资源未就绪时，文件区域允许暂缺。

## 验证边界

本轮以合成 G-code、模拟 Klipper/RRF HTTP、临时 SQLite、隔离 Cloud MySQL/EMQX 及 Flutter 测试验证。实际执行结果另在交付记录登记。已有打印文件未包含层标记、切片估算或缩略图时，对应内容继续未知。正式安装、Cloud 服务升级及真实打印过程的端到端验收需要单独执行。

## 本轮验证记录

2026-11-07，Server 完整 `mvnw.cmd -q verify` 共950项通过，失败、错误及跳过均为0。包含30项文件来源测试，覆盖 Klipper、RRF、PNG/JPEG/QOI、临时图片失败重试及有界下载。缓存、删除交错、执行结束或连接变化后的丢弃、数据来源、HTTP部分失败与取消截止时间分别有回归测试。

Server 代码按 `4917323`、`1c08ba6`、`553154d`、`5e5ce9f`、`181c222`、`3b8e01e` 分点提交。最终验证 JAR 为 `target/fabmatrix-server-1.0.0.jar`，SHA-256 为 `8b95e70f47344440fa9ea0da54666440f3b23cc0ac07486d9dd7e95b29c0d480`。

Cloud 完整验证123项通过，Flutter 常规测试298项通过、1项既有外部用例跳过，静态分析通过。联合 C1 共52项通过，覆盖真实 Server 进程自动上传确认、隔离 MySQL V24持久化、EMQX TLS/ACL、Cloud授权读取，以及 Flutter 实际 HTTP、PNG解码和详情展示。32台模拟设备连续65秒检查65次，最大观测年龄23,310ms，过期阈值30秒。完整证据见 [Cloud 验证记录](../../fabmatrix-cloud/docs/GCODE_FILE_METADATA.md)。

当前连接的 PJE110 开发 App 已通过 CLI 热重载与热重启验证。本轮服务验证使用隔离环境，现场设备的双固件打印过程及正式安装升级验收仍待执行。开发 App 的热重载结果属于当前调试会话，持久安装需要重新打包。

## 现场升级与手机核对

2026-11-07后续完成实际部署。常驻Cloud从V23升级V24，手机正式包与开发包均保留数据安装为1.0.0+16。Windows安装器使用既有默认目录生成，基于提交 `e25af58`，文件为 `dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe`，SHA-256为 `24876efd787f64fa5e7883ede328ee244b2806951b6e313024b97da4316ec278`。

已覆盖升级 `D:/Program Files/FabMatrix`。安装退出码0，服务Running、健康UP，安装JAR SHA-256与本轮950项验证产物一致，网关凭据文件升级前后摘要一致。脱敏结果保存在忽略的 `target/gcode-installed-server-upgrade.json`，安装日志为 `target/gcode-server-install.log`。

真实Klipper设备Printer_46B6正在打印，Cloud接收1条执行文件元数据与2张PNG。PJE110开发版实际显示绿色模型预览、OrcaSlicer 2.3.1-beta、切片预计69小时17分钟及总层数1800。图片为32×32和300×300，合计33,849字节。截图保存于App仓库的 `build/qa/gcode-phone-live-preview.png`。该步骤只读取设备与页面。

这台设备的当前层数仍为UNAVAILABLE。设备没有报告当前层，文件大小411,187,499字节，超过64MiB远程完整G-code索引采集上限，因此按文件位置推算当前层所需的索引暂缺。总层数、估算和图片均已采集，不能把文件字节进度当作精确层数。RRF现场执行文件采集继续等待可用真实执行验收。
