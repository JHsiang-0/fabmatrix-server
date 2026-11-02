# Klipper / RRF 统一设备模型与现场改造方案

日期：2026-11-01。产品仍为 `1.0.0`；归属 C2 云只读扩展设计。`modelVersion=2` 是数据契约版本，不是产品 v2，也不表示启用 v3 自动派单。

状态：**设计与契约验证完成，Server P1a 的内部观测基础已实现，P1a 尚未完成**。现有 LAN API、设备动作、数据库结构和 Cloud V1 上报继续以实际代码为准。

## 1. 统一契约入口

共享定义只在 Cloud 仓库维护，避免三个仓库各自定义相似但语义不同的对象：

- [完整模型入口](../../fabmatrix-cloud/docs/data-model/README.md)
- [领域与状态边界](../../fabmatrix-cloud/docs/data-model/UNIFIED_DEVICE_MODEL.md)
- [系统对象字典](../../fabmatrix-cloud/docs/data-model/PRODUCTION_OBJECTS.md)
- [固件映射与证据](../../fabmatrix-cloud/docs/data-model/FIRMWARE_MAPPING.md)
- [存储/兼容/验收](../../fabmatrix-cloud/docs/data-model/STORAGE_AND_EVOLUTION.md)
- [机器可读 Schema](../../fabmatrix-cloud/docs/protocol/schema/printer-observation-v2.schema.json)

模型不等于直接上报固件全部对象。固件原始字段先经过 Server 适配、时间/单位校验和敏感字段白名单，Cloud 接收后再根据授权投影给手机和桌面客户端。

## 2. 当前已经具备的基础

- [PrinterProtocolAdapterFactory](../src/main/java/com/example/farm/protocol/PrinterProtocolAdapterFactory.java) 按固件选择适配器；Klipper 与 RRF 客户端独立。
- [PrinterDeviceStatus](../src/main/java/com/example/farm/protocol/PrinterDeviceStatus.java) 已统一基础状态、温度、时长、文件进度和部分设备执行证据。
- [PrinterExecution](../src/main/java/com/example/farm/entity/PrinterExecution.java) 已持久化实际执行与平台任务的关系，支持外部打印、重启恢复与终态确认。
- [CloudSnapshotSource](../src/main/java/com/example/farm/cloud/CloudSnapshotSource.java) 只上传白名单基础状态；现有消息版本为 1。
- 现场 [CLOUD_GATEWAY](CLOUD_GATEWAY.md) 中的只读上报不调用设备控制，保留此边界。

## 3. 已发现需优先修复的语义缺口

1. `MoonrakerApiClient` 多个缺失数值补 0，V2 必须直接从原始对象区分真实 0、未知和不支持。
2. `RrfApiClient` 将切片预估 `job.file.printTime` 填入 totalDuration，需与真实运行时长拆开；lastFileName不能无条件作为当前任务。
3. RRF 温度映射只取一个工具/热床，并有缺配置时猜测热床的兼容分支；新模型使用工具与heater显式关联，不猜角色。
4. 现有状态缓存10秒、dev空闲轮询15秒，且云快照从数据库lastSeenAt与缓存值组合；新模型按同一帧存储测量和时间，不能仅增加字段。
5. 当前网关内数字ID映射不适合设备跨网关迁移，需要可持久设备UID及来源实例UUID；保留现有云UUID。

## 4. Server P1a 实施边界

### 当前进度

Server 已在现有 Klipper 和 RRF 状态读取中生成内部 `PrinterObservation`，包括采集时间、状态、加热器、工具、执行摘要及数值质量；监控将其写入独立的 90 秒短期缓存。缺失与真实零值分开，RRF 按显式关联及加热模式解释目标温度，区分运行时长与切片预估。旧 LAN API、WebSocket 与 Cloud V1 保持原有字段和语义。

V2 受限快照上报已增加为默认关闭的可选路径，Cloud 和手机可消费该子集；启用方式与部署顺序见 [CLOUD_GATEWAY](CLOUD_GATEWAY.md)。这是 transport `schemaVersion=2` 的初步实现，不是 Cloud 文档中完整 canonical device object：设备稳定身份、descriptor、分页/分片及独立详情 API 仍未实现。

当前只是基础：Klipper 仍查询现有固定对象，未完成动态发现；RRF 多次请求仅标记部分采集，尚未做执行变化后的重采；设备稳定身份、descriptor、执行 UUID、持久化、离线及监控关闭时的数据策略均未完成。缓存有过期时间，不能当作持久化数据或设备当前在线的证明。下面列出的是 P1a 的完整目标，而非已交付内容。

新增 `PrinterObservationView` 作为内部读取策略：调用方必须提供最新设备健康信息、当前时间和最大观测年龄；监控关闭、离线、通信失败、数据过期、时间异常或采集不一致时只保留最后已知值，不提供当前值。该策略尚未连接 API 或云端；最后已知值仍只保留在短期缓存中，不能保证重启后可用。Klipper 当前固定查询未读取选中工具，因此不再推断 `extruder` 一定是当前工具。

### 完整目标

先添加固件无关的 `protocol/model`（或明确领域子包）对象：PrinterDescriptor、PrinterObservation、Measurement、ToolDescriptor、HeaterDescriptor、ExecutionSummary。具体 Java 名称与现有类型冲突时按作用加前缀，不创建空 Service/Mapper。

适配器输出新模型，现有 `PrinterDeviceStatus`/`MoonrakerStatusDTO` 通过兼容映射继续服务现有监控和 LAN 界面；核心统一对象不再叫 Moonraker。不要一次重写任务控制事务、设备锁和现场安全流程。

- 按实际加载对象发现能力，不依固件family假定所有字段存在。
- 每组采集记录时间；RRF跨HTTP查询期间执行变化则标不一致并重采。
- 持久最新观测及descriptor版本，缓存只是加速；每个测量的真实观测时间不能因读取缓存而刷新。
- 设备离线/监控关闭时保留执行会话未决事实，公开当前测量为空；历史值通过独立lastKnown展示。
- 当前执行沿用 `farm_printer_execution`，新增稳定UUID映射，恢复过程不要每轮创建新的执行。
- 本地IP/MAC/API Key和原始故障响应只留现场私有对象；日志与对外VO继续脱敏。

后续 P1b/P1c 才接入 Cloud 双版本、精确MQTT ACL、版本协商、增量存储与手机详情。此前不得通过给 V1 DTO直接加字段发送新消息。

## 5. 数据库与部署

本轮不生成可误执行的迁移SQL、不修改真实库、不重启设备/服务、不覆盖安装包。新增持久表/UID回填须在P1实现时分别提供MySQL脚本和SQLite幂等升级，保持数字ID、任务关系及现有Cloud UUID。

Server仍是本地单农场身份域；组织隔离由Cloud承担。不要把Cloud用户表复制进Server，不建立整库双向同步。云端异常不能影响本地登录、轮询与现场执行。

## 6. 当前验证与后续验证

设计阶段使用合成数据校验 Schema、引用、时间、温度模式、执行来源和敏感字段边界；具体命令见 Cloud 模型入口。内部观测基础在隔离副本中执行 `mvnw.cmd test`，801 项通过；未运行固件物理动作，也未验证真实设备采集。

P1修改Java/SQL时至少运行 `mvnw.cmd test`；Cloud运行 `mvnw.cmd verify` 和隔离MySQL/MQTT升级回归。客户端补齐类型映射与小屏/离线/权限验证后重新构建安装包。真实Klipper/RRF各一台只读验证分别登记版本/模式，emulated设备不作为生产物理验收依据。
