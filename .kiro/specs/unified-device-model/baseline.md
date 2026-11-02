# 统一设备模型跨仓库基线与字段覆盖

盘点日期：2026-11-02。本文记录代码、Schema 和工作区状态，不把设计字段写成已实现能力。

## 仓库状态

| 仓库 | 当前实现事实 | 本规格前需注意 |
|---|---|---|
| `fabmatrix-server` | SQLite 本地单实例；Klipper/Moonraker 与 RRF 适配器；内部 `PrinterObservation` 有短期缓存；`farm_printer_execution` 有本地持久执行生命周期；V1 默认云上报，受限 V2 可选 | 本地协议 API、任务安全流程和旧 Cloud 消息需保持兼容；此前没有稳定设备/来源 UUID |
| `fabmatrix-cloud` | MySQL/Flyway；V1 和受限 V2 入口；旧农场设备投影及受限单设备查询；组织/农场授权 | 当前工作区含未提交的模型 DTO、V5 迁移、控制器/DAO/Service 和文档改动。上述文件按用户工作区处理，未覆盖、未提交、未部署；V5 是否已在常驻开发库执行须由迁移历史核实 |
| `flutter_application` | Cloud 设备列表、受限 V2 详情 DTO、详情页与独立详情请求 | 没有完整 descriptor、执行历史、事件、告警或趋势数据模型；已有用户改动未覆盖 |

## 字段矩阵

质量缩写：`GOOD` 为本帧有效观测；`UNAVAILABLE` 为已支持但本次未取到；`UNSUPPORTED` 为已确认无读取能力；`INVALID` 为设备返回无效/越界值；`NOT_APPLICABLE` 为当前场景不适用。未知能力不得伪造成空集合或真实 0。所有设备时间为 UTC；数值单位写在规范字段名中。

| 领域 / 字段组 | 权威来源与所有者 | 完整模型及存储/传输要求 | 当前覆盖和验收边界 |
|---|---|---|---|
| 来源身份：`sourceInstanceId`、`deviceUid`、本地数字 ID、Cloud UUID | Server 持有来源谱系和设备 UID；Cloud 分配公开 UUID 并持有网关/农场绑定 | UUID 稳定；Cloud 唯一映射 `(sourceInstanceId, deviceUid)`，旧 Cloud UUID 保留；不可按 IP/MAC/名称合并 | 本次 Server 已开始 SQLite 持久化；Cloud 回填/转移、克隆冲突处理仍待实现与迁移测试 |
| 资产：名称、机台号、位置、登记/归档 | Server 配置为权威 | 低频版本化；云端只读投影；停监控/不在完整库存不等于删除 | Server `farm_printer` 有名称、机台号、网格位置；Cloud 只有旧快照名及存在标记；无统一资产版本 |
| Descriptor：固件版本/连接模式/仿真/机器模式；工具、加热器、风扇、轴、传感器及关联；能力状态/证据 | 设备对象提供事实；Server 适配器发现并规范化 | 支持 `SUPPORTED/UNSUPPORTED/UNKNOWN` 和 `PROBED/CONFIGURED/ADAPTER`；通道 ID 稳定；受限 Schema JSON 持久化且带 revision | Server 无 descriptor 持久化/动态发现；内部观测只含少量 heater/tool；Cloud V2 无完整 descriptor；App 无 descriptor 页面 |
| Observation：帧 ID、设备代次、revision、描述版本、开始/结束时间、一致性、链路、系统/运行状态、问题码 | Server 从协议适配器观测；网关接收时间由 Cloud 生成 | 原子整帧；`COMPLETE/PARTIAL/INCONSISTENT`；跨请求变化必须标不一致；当前与 last-known 分开 | Server 内部有 capture/observed/completeness/system/operation 及短期缓存；没有帧 ID/revision/持久最新帧；V2 仅受限投影；App 消费该子集 |
| 测量：温度/目标/模式、风扇比例与 RPM、轴位置/归零、速度/流量倍率、传感器 | 对应固件对象；Server 按字段映射并标质量/来源/单位 | 值、质量、采集时刻独立；真实 0 可表达；各通道不跨帧拼接 | Server parser 覆盖部分温度/目标/进度；V2 只 heater/tool 及部分执行数值；风扇、运动、传感器无完整采集和 Cloud 持久化 |
| 执行：执行 UUID、来源/种类、现场任务引用、首次发现/开始、文件摘要、进度来源、时间分项、层数、耗材、剩余估算和终态证据 | 固件提供运行事实；Server `farm_printer_execution` 为执行会话权威；平台任务与执行分离 | 执行 ID 持续且重启稳定；未知来源/终态不可推断；文件仅 basename/已验证摘要；估算需标来源 | Server 有数字执行行和终态核验，但未持久 UUID；内部/V2 observation 仅含仿真、若干测量；Cloud 无独立执行对象；App 只显示受限摘要 |
| Event / Alert / acknowledgement / telemetry sample | Server 产生日志式设备事件；Cloud 告警规则/人工确认及云端查询授权；采样策略需统一 | 事件 UUID、去重/顺序/outbox/ACK；告警生命周期和确认审计；采样降采样与保留预算分别持久化 | 三端目前只有设计/历史状态记录，没有完整事件 outbox、告警实例/确认或 Cloud 趋势模型/API/App |
| 任务/文件/用户/权限 | 本地任务、文件、用户由 Server 管理；Cloud 组织、成员、农场权限由 Cloud 管理 | 不双向复制业务库；设备公共快照不能暴露云用户或本地用户 ID；详情、历史逐请求检查农场权限 | 两边已有不同账号/任务模型和权限边界；任何完整执行接口不得把 `farm_job_id` 当 Cloud 资源 ID |
| 远程命令、媒体/文件传输 | 不属于本只读观测上报 | C3/媒体独立契约、授权、审计和传输；本任务不增加设备控制路径 | 未实现；模型 Schema 或能力字段不代表远程动作已授权 |

## Canonical Schema 字段级映射

此表以 Cloud `docs/protocol/schema/printer-observation-v2.schema.json` 的 canonical `modelVersion=2` 字段为清单，不代表字段已有采集或 API。可空性指协议语义；canonical 对象要求键存在，未知值通常以 `null` 与 measurement quality 表达。所有当前/历史公开查询均由 Cloud 根据已认证用户的农场范围授权；网关 MQTT 身份只允许写入其当前绑定设备。全对象均不得含设备凭据、私有网络地址、原始固件响应或本地绝对路径。

| JSON 字段 | 来源、单位与质量 | 可空性、持久化及传输 | 当前覆盖与验收 |
|---|---|---|---|
| `modelVersion` | 固定 canonical schema 整数；非物理测量 | 必填；随 payload；与产品、MQTT、REST 版本分离 | 文档/schema 有；完整 wire 解码无 |
| `identity.sourceInstanceId` | Server SQLite 安装谱系 UUID | 必填；Server 安装身份表；V3 消息受当前网关绑定授权 | Server T1.1 已持久化；尚未上报/Cloud 映射 |
| `identity.deviceUid` | Server 首次登记生成的稳定 UUID | 必填；`farm_printer.device_uid` 唯一索引；不取代本地数字 ID 或 Cloud UUID | Server T1.1 已迁移；Cloud 尚未验证冲突回填 |
| `identity.localPrinterId` | Server 本地自增 ID 的十进制字符串 | 必填；留兼容关联；不能脱离 source instance 单独作为身份 | 现有 V1/V2 localPrinterId 存在；尚未与 deviceUid 一起发送 |
| `descriptor.revision` | Server 对设备描述拓扑/配置变更递增 | 必填正整数；描述持久化并供观测引用 | 未实现 |
| `descriptor.name`, `machineNumber`, `locationLabel`, `configuredMaterial`, `configuredNozzleDiameterMm` | 现场配置；尺寸单位 mm | 字符串/数值可空按 Schema；资产当前值持久化，低频 descriptor 传输 | Server 现有一部分字段；Cloud/App 不支持完整 descriptor |
| `descriptor.firmwareFamily`, `firmwareVersion`, `connectionMode`, `machineMode`, `emulated` | 适配器/固件探测；布尔可因无法探测而为空 | 未知与不支持不得假设；低频持久化及 descriptor 消息 | Server 仅能识别部分 family；版本/模式/仿真未完整发现 |
| `descriptor.tools[]`: `id`, `name`, `heaterIds`, `fanIds`, `extruderIds` | 固件配置或对象发现；ID 稳定，不以数组位置作为永久身份 | 拓扑已成功发现时允许空数组；读取失败沿用最后 Descriptor、增加 issue；revision 化传输 | Server parser 仅内部工具和 heater 关系子集；无持久 Descriptor |
| `descriptor.heaters[]`: `id`, `name`, `role`, `roleEvidence` | 固件配置/适配器；温度后续单位 ℃ | role unknown 必须表达；描述集合与 measurement 分离 | Server 部分可观测但无完整证据和 Descriptor |
| `descriptor.fans[]`: `id`, `name`, `role`; `descriptor.axes[]`: `id`, `name` | 固件配置/对象发现；无数值单位 | 能力未知不可伪造空数组；低频 descriptor | 未实现 |
| `descriptor.sensors[]`: `id`, `name`, `kind`, `unit`; `descriptor.capabilities[]`: `code`, `support`, `evidence`, `detectedAt` | 设备发现/适配器；能力 `SUPPORTED/UNSUPPORTED/UNKNOWN`，证据 `PROBED/CONFIGURED/ADAPTER` | 可发现集合需有界；不得混入用户授权；按 Descriptor revision 传输 | 未实现 |
| `observation.id`, `deviceBootId`, `revision`, `descriptorRevision` | Server 生成帧 UUID、连续代次 UUID 和设备级序号 | 必填；最新观测持久化；Cloud 按 identity+revision 幂等/拒绝旧帧 | Server 当前只有进程级网关 boot/epoch/sequence，不是设备帧/设备代次 |
| `observation.captureStartedAt`, `observedAt` | Server UTC 时钟；采集开始/结束 | 必填 UTC；各 measurement 的观测时刻独立；完整帧持久化/单设备传输 | Server 有内部时间但无持久帧 ID；V2 有 capture/observed |
| `observation.completeness`, `systemState`, `operationState` | 固件映射；不确定为 `UNKNOWN`，跨请求更改为 `INCONSISTENT` | 必填枚举；完整性不可空；持久最新帧并按权限详情返回 | Server 有内部子集；V2 有状态但不含完整生命周期 |
| `observation.connection.status`, `monitorEnabled`, `lastSuccessfulAt`, `consecutiveFailures` | Server 链路健康/监控配置；不是固件系统状态 | 必填；失败计数非负；时间为空表示未成功；和状态帧共同持久化 | Server farm_printer 保存；V2 仅 status/monitor 旧投影，未组成 canonical observation |
| `observation.currentToolId` | 固件选中工具对象，若不可读为空 | 可空；不能默认 `extruder` 或第一个工具；帧内引用 Descriptor | 当前 Klipper 未读取选择工具；RRF 部分可得 |
| `observation.heaters[]`: `id`, `currentC`, `effectiveTargetC`, `activeTargetC`, `standbyTargetC`, `pwmRatio`, `mode` | 固件通道；℃、PWM ratio 0..1；每值含 value/quality/observedAt/source | ID 必须属于对应 Descriptor；0 是有效值；不适用/缺失以质量表达；当前帧替换存储 | Server 部分温度/目标值；V2 部分 heaters 无 pwmRatio/来源 |
| `observation.fans[]`: `id`, `pwmRatio`, `rpm` | 固件风扇通道；比例 0..1，转速 rpm | rpm 可为空/UNSUPPORTED；不从 PWM 推算 rpm；通道引用 Descriptor | 未实现 |
| `observation.motion.coordinateSpace`, `axes[].(id, positionMm, homed)`, `speedFactor`, `flowFactor` | 固件运动对象；轴位置 mm，倍率按 ratio | 不混淆 machine 与 G-code 坐标；各测量带质量；每帧有界 | 未实现 |
| `observation.sensors[]`: `id`, `enabled`, `reading` | 固件传感器；单位继承 descriptor.sensors[].unit | 未配置/不可读与“未检测到耗材”分开；每值带质量/来源 | 未实现 |
| `observation.currentExecution` 与 `lastExecutionResult` | Server 执行会话结合固件事实；执行 UUID稳定 | 当前无执行为 null；终态与实时状态分开；不能因离线推断完成 | Server 已有本地行但无 execution UUID；云/App 尚无独立对象 |
| `execution.id`, `source`, `kind`, `localFarmJobId`, `firstObservedAt`, `startedAt` | Server 执行状态；平台任务引用为本地 ID 字符串 | 来源/开始时间可能 UNKNOWN/null；本地任务不作为 Cloud 用户身份；授权查询时按任务权限裁剪 | Server 持久化部分来源/开始时刻；完整字段无 |
| `execution.file.(name,sizeBytes,sha256)` | 现场文件元数据；字节数 byte、摘要 SHA-256 | 字段可空；仅 basename；不计算就为空；路径/下载 key 禁止传输 | Cloud 当前旧状态带脱敏文件名范围有限；完整文件摘要未实现 |
| `execution.progressPercent`, `progressMethod`, `filePositionBytes`, `timing.*`, `layers.*`, `filamentLengthMm`, `estimatedRemaining.(seconds,method,confidence)` | 固件值、file offset、Server 计算或 slicer estimate；%、byte、秒、层、mm | 每个 measurement 有质量、时间、来源；active/warmup/paused/slicer time 不互换；confidence 0..1 | Server/V2 仅部分 duration/progress/耗材；层数、质量、estimate method 未完整实现 |
| `lastExecutionResult.executionId`, `outcome`, `kind`, `evidence`, `endedAt`, `observedAt`, `reasonCode` | Server 已结束执行及终态确认 | 未确认 outcome 必须 UNKNOWN/UNCONFIRMED；不可从 idle 或 100% 单独推断完成 | Server 现有终止核验未映射至完整 Cloud 对象 |
| `observation.issues[]`: `code`, `severity`, `message` | 适配器产生受限问题代码；不承载原始响应 | 数量和文本有界；敏感值脱敏后可随观测传输 | 未实现统一 issue 列表 |
| Event、Alert、sample/history | Event 从现场状态/协议变化；Alert 由 Cloud 规则派生；sample 从观测降采样 | 独立对象/表/权限/保留；event outbox 有序、持久、ACK；不放入周期完整帧 | Schema 外围对象定义尚不完整；三端均未形成端到端实现 |

## 版本与协议事实

- 产品版本 `1.0.0`、canonical payload 的 `modelVersion=2`、MQTT envelope 的 `schemaVersion`、REST API 版本是不同版本域。
- 当前 MQTT 已部署/开发实现：V1 默认发送；`schemaVersion=2` 代表兼容旧快照携带受限 observation，不是完整 canonical schema。Cloud 与 Flutter 有受限 V2 支持。
- Cloud `docs/protocol/schema/printer-observation-v2.schema.json` 定义 canonical `modelVersion=2`，不表示 MQTT V2 已符合此 schema。
- 决策候选：完整传输使用新的 MQTT `schemaVersion=3`，payload `modelVersion=2`；V1/V2 解码与语义不变。V3 的协商、精确 topic/ACL、ACK、库存提交门禁仍需 T0.4 冻结后实现；本基线不启用上报。

## 未能从代码确认的事项

1. 哪些固件版本/对象组合在实际 Klipper 与 RRF 设备上可读，需要只读真机证据；文档/fixture 不构成现场支持证明。
2. Cloud 工作区的未提交 V5 是否已在常驻 MySQL 执行，以及现有 UUID 数据量/冲突数，必须查 `flyway_schema_history` 和只读报告后再决定迁移步骤。
3. SQLite 备份副本克隆成第二台独立 Server 的用户流程尚未定义；不能自动重置来源谱系，也不能允许并发复制品共用身份。
4. 告警触发规则、历史留存和手机页面需求尚未有最终业务阈值；不能用示例保留期作为已批准配置。

本次代码仅增加 Server SQLite 来源实例 UUID 和设备稳定 UUID 的初始化/兼容迁移；未读取真实 SQLite、Cloud 数据库或已安装服务，也未启用新模型上报。
