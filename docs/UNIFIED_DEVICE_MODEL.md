# Klipper / RRF 统一设备模型与现场改造方案

日期：2026-11-01。产品仍为 `1.0.0`；归属 C2 云只读扩展设计。`modelVersion=2` 是数据契约版本，不是产品 v2，也不表示启用 v3 自动派单。

当前状态（2026-11-04）：本规格 35 项完成，Server、Cloud 与 Flutter 已串联 canonical 持久化、V3 协商、库存、描述、观测、执行、事件和历史。两种真机及 Android 已验证打印、明确取消、换网恢复和离线展示，现场已安装 Server `de6a41c` 和 Flutter `3e0c536`。最终审查补齐文件计数、层数、RRF 独立通道及二维热床映射，857 项 Server 检查、81 项 Flutter 常规测试和最终 44 项隔离检查通过；新增通道已做空闲只读复验。V3 默认关闭，告警自动规则尚未实现。字段覆盖、版本及未实测边界见 [最终审查](UNIFIED_MODEL_FINAL_REVIEW.md)，完成状态以 [Kiro 任务清单](../.kiro/specs/unified-device-model/tasks.md) 为准。

本文下方保留 2026-11-01 至 T2.7 的设计和阶段记录，其中“尚未实现”“现有版本”等陈述描述当时状态，不能作为当前部署或能力清单；部署与开关以 [CLOUD_GATEWAY.md](CLOUD_GATEWAY.md) 为准。

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
- `farm_printer_execution.execution_uid` 为执行记录增加稳定 UUID；旧行启动时幂等回填，`first_observed_at` 保留首次观测时间，外部执行不会据此伪称真实开始时间。
- [CanonicalPrinterModel](../src/main/java/com/example/farm/protocol/model/CanonicalPrinterModel.java) 与 Mapper/Validator 对齐 Cloud canonical Schema 的对象层级；映射仅使用当前可证明字段，缺少稳定执行记录时拒绝拼装当前执行。
- `farm_printer_canonical_state` 只保留每台设备最新 canonical JSON、descriptor 快照与版本号；每进程新建 `deviceBootId`，首帧标记连续性未知。失败/不一致采集不覆盖最后持久观测。
- [CloudSnapshotSource](../src/main/java/com/example/farm/cloud/CloudSnapshotSource.java) 只上传白名单基础状态；现有消息版本为 1。
- 现场 [CLOUD_GATEWAY](CLOUD_GATEWAY.md) 中的只读上报不调用设备控制，保留此边界。

## 3. 已发现需优先修复的语义缺口

1. `MoonrakerApiClient` 多个缺失数值补 0，V2 必须直接从原始对象区分真实 0、未知和不支持。
2. `RrfApiClient` 将切片预估 `job.file.printTime` 填入 totalDuration，需与真实运行时长拆开；lastFileName不能无条件作为当前任务。
3. RRF 温度映射只取一个工具/热床，并有缺配置时猜测热床的兼容分支；新模型使用工具与heater显式关联，不猜角色。
4. 现有状态缓存10秒、dev空闲轮询15秒，且云快照从数据库lastSeenAt与缓存值组合；新模型按同一帧存储测量和时间，不能仅增加字段。
5. Server SQLite 已在 `LocalSchemaMigration` 持久生成 `sourceInstanceId`，并为既有/新增设备生成唯一 `deviceUid`；Cloud 身份映射、旧 Cloud UUID 回填、跨来源迁移与数据库克隆冲突策略尚未实现。继续保留本地数字 ID 与已有 Cloud UUID。

## 4. Server P1a 实施边界

### 当前进度

Server 已在现有 Klipper 和 RRF 状态读取中生成内部 `PrinterObservation`，包括采集时间、状态、加热器、工具、执行摘要及数值质量；监控将其写入独立的 90 秒短期缓存。缺失与真实零值分开，RRF 按显式关联及加热模式解释目标温度，区分运行时长与切片预估。SQLite 现有库启动时会幂等建立 `farm_installation_identity.source_instance_id`、回填 `farm_printer.device_uid` 并加唯一索引；新设备在服务新增路径生成 UUID。旧 LAN API、WebSocket 与 Cloud V1 保持原有字段和语义。此身份基础尚未意味着已发送给 Cloud，备份克隆成独立站点的身份分裂流程仍未实现。

V2 受限快照上报已增加为默认关闭的可选路径，Cloud 和手机可消费该子集；启用方式与部署顺序见 [CLOUD_GATEWAY](CLOUD_GATEWAY.md)。这是 transport `schemaVersion=2` 的初步实现，不是 Cloud 文档中完整 canonical device object：设备稳定身份、descriptor、分页/分片及独立详情 API 仍未实现。

当前实现已覆盖第一版固件采集与持久化：Klipper 按 Moonraker 已加载对象白名单查询多加热器、风扇、轴和耗材传感器；RRF 使用 `flags=f` 请求实时字段，并在多组请求前后复读任务身份以识别采集期间切换。固件 family 仍没有可靠版本信息，真实 Klipper/RRF 设备尚未验收。descriptor revision、设备启动代次/观测 revision、最新观测和执行生命周期均持久化；读取最新 canonical 模型时按当前设备状态覆盖连接健康，监控关闭或采样超过 30 秒返回 `UNKNOWN`，明确离线返回 `OFFLINE`，保留原测量时间和最后执行结果。Server 已有受限事件对象、SQLite 持久 outbox，并在连接健康跨越在线/离线边界时生成设备连接事件；执行生命周期和监控关闭事件在对应业务事务内写入 outbox，保证事务回滚时不会留下孤立事件。Cloud 发送与提交 ACK 尚未接通。专用 last-known 查询 API、Cloud 完整上报仍未完成。缓存有过期时间，不能当作持久化数据或设备当前在线的证明。下面列出的是 P1a 的完整目标，而非已交付内容。

新增 `PrinterObservationView` 作为内部读取策略：调用方必须提供最新设备健康信息、当前时间和最大观测年龄；监控关闭、离线、通信失败、数据过期、时间异常或采集不一致时只保留最后已知值，不提供当前值。该策略尚未连接 API 或云端；最后已知值仍只保留在短期缓存中，不能保证重启后可用。Klipper 当前固定查询未读取选中工具，因此不再推断 `extruder` 一定是当前工具。

### 完整目标

继续扩展已有固件无关 `protocol/model` 对象，补上动态发现、能力、风扇/轴/传感器、测量窗口、版本校验及与 SQLite 持久层的集成；暂不创建空 Service/Mapper。

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

### T2.7 合成字段覆盖与兼容边界

| 领域 | 合成 fixture / 生命周期覆盖 | 当前明确缺口 |
|---|---|---|
| Klipper 状态/测量 | `PrinterObservationParserTest`、`KlipperMoonrakerAdapterTest`、`MoonrakerObservationObjectSelectionTest`：状态、真实零值/缺失/非法值、多加热器/工具、风扇、轴、耗材传感器和执行时间口径 | 固件版本、所选工具以及未加载对象的能力无法可靠推断；当前工具保持未知 |
| RRF 状态/测量 | `PrinterObservationParserTest`、`RrfApiClientTest`、`RrfAdapterTest`：多请求字段、工具/加热器关系、OFF/standby、活动/切片预估时间、风扇、轴/传感器和采集期间任务身份变化 | RRF 固件版本及连接运行模式不可靠；缺层数、完整耗材统计与部分固件端事件 |
| 执行与恢复 | `LocalSchemaUpgradeIntegrationTest`、`PrinterExecutionServiceTest`：稳定执行 UUID、来源、重启、未知终态、current/last execution 以及 SQLite 服务重建 | 文件摘要 SHA-256、层数及设备无法提供的执行估算保持缺省/未知；不可据此宣称完整制造履历 |
| 持久化与连接健康 | `LocalSchemaUpgradeIntegrationTest`：旧 SQLite 增量迁移、重复迁移、revision/boot 恢复、离线/停监/过期投影、outbox 去重/容量/ACK 清理及外层事务回滚；`PrinterExecutionServiceTest`：执行开始/暂停/恢复/终止及稳定 execution UUID；`PrinterMonitorConfigurationServiceTest`：事务内记录监控关闭事件；`PrinterEventOutboxHealthTaskTest`：积压警告与恢复迟滞 | 本地 outbox 生产连接/断开/监控关闭和执行生命周期事件；执行及监控关闭事件与本地业务事务原子写入；容量达到 80% 告警、低于 60% 恢复。尚未发送到 Cloud 或完成 ACK 集成；连接事件不在监控状态更新事务中生成，尚无断网恢复实链路验收 |
| 旧接口兼容 | `PrinterObservationParserTest.internalObservationDoesNotChangeLegacyStatusJson`、`PrinterVOTest`、`PrinterMonitorControllerTest`、`WebSocketSecurityTest`、`WebSocketEventPublisherTest`：保持旧状态 DTO/VO 与 WebSocket 契约 | 尚无 LAN canonical 详情 API；完整对象不应被描述为现有 LAN/API 已可读取 |

上表的测试都使用合成响应或临时 SQLite；它们证明映射和回归边界，不证明现场固件行为。Server 全量 `mvnw.cmd test` 最近一次为 831 项通过（0 failures/errors/skips）。Klipper、RRF 真机只读验收仍属于 T6.3；`mvnw.cmd package`/安装版及现场数据链路验收属于 T6.2/T6.4。

P1修改Java/SQL时至少运行 `mvnw.cmd test`；Cloud运行 `mvnw.cmd verify` 和隔离MySQL/MQTT升级回归。客户端补齐类型映射与小屏/离线/权限验证后重新构建安装包。真实Klipper/RRF各一台只读验证分别登记版本/模式，emulated设备不作为生产物理验收依据。
