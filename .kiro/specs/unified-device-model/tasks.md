# 统一设备模型完整落地 Kiro 任务

状态：进行中；创建日期：2026-11-02。任务属于跨仓库只读模型建设，先模型、后完整上报。勾选必须附实际证据。需求和约束见同目录 `requirements.md`、`design.md`。

仓库基线整理（2026-11-02）：Cloud 历史账号/资源管理改动补交为 `4bcb296`，统一模型分支通过 `d764b85` 合入原工作区，两个 Cloud worktree 已同步到该提交；整合后 `mvnw verify` 73 项、隔离 MySQL/EMQX/Server JAR 检查 23 项通过。Flutter 历史账号页面、设置及云机器列表接入补交为 `49df3fa`，文档检查、静态分析和全量 75 项测试通过。以下早期记录中“保留未提交改动”描述的是当时的状态，后续开发以本次已提交基线为准。本次未推送或部署。

## T0 基线、范围和契约冻结

- [x] T0.1 盘点 Server、Cloud、Flutter 的现有实体、协议字段、调用关系、持久化、已部署版本与未提交修改；区分已实现、设计和缺口。对应 R1、R5、R6。证据：同目录 `baseline.md`；Cloud 未提交改动按用户工作区保留，未部署状态未臆断。
- [x] T0.2 审核现有完整模型、固件映射和 Schema，建立逐字段矩阵：来源、单位、质量、可空性、授权、持久化、上报及验收；定义完整交付范围，不把未知字段伪装为已支持。对应 R1、R3。证据：同目录 `baseline.md` 的 canonical 字段级映射及各仓库当前覆盖；固件真实设备覆盖仍需 T6 验收。
- [x] T0.3 冻结来源实例、设备 UID、Cloud 设备 ID、网关重绑/转移与克隆冲突决策，以及旧数据的映射、回填、恢复和审核策略。对应 R2。证据：Cloud ADR 0010；旧映射不按本地 ID 猜测，克隆分叉和管理员认领规则已明确。
- [x] T0.4 冻结完整协议版本/协商、字段兼容、分片/ACK/去重、限额和回退；既有 V1、受限 V2 不改变含义。对应 R1、R6。证据：Cloud ADR 0010 与更新后的协议/存储路线文档；canonical modelVersion=2，完整 transport schemaVersion=3，实际支持仍待实施。
- [x] T0.5 冻结事件、告警、采样保留和权限边界，以及本轮设备模型与远程控制、视频、文件传输的接口边界。对应 R1、R4、R7。证据：Cloud ADR 0010；告警由 Cloud 规则派生，事件需持久 outbox/提交 ACK，未批准的阈值/保留期不写死，本期不含控制/媒体。
- [x] T0.6 更新跨仓库事实文档和 ADR，清理与当前 SQLite、受限 V2 和独立详情接口不一致的说法；记录尚待用户确认的重大决策。对应 R1、R8。证据：本规格 `baseline.md`、Server `docs/UNIFIED_DEVICE_MODEL.md`、Cloud 模型/协议文档及 ADR 0010；Cloud 未提交文件仍按用户工作区保留。

## T1 身份和数据迁移基础

- [x] T1.1 Server 为来源实例和设备增加持久稳定 ID；实现已有 SQLite 增量升级与全新库初始化，兼容现有数字 ID 及业务关系。对应 R2、R5。证据：`farm_installation_identity` 单例、`farm_printer.device_uid` 唯一索引、旧库自动回填、服务新增设备生成 UUID；`LocalSchemaUpgradeIntegrationTest` 覆盖既有任务引用、重复迁移稳定性。
- [x] T1.2 Cloud 新增必要的身份、来源与绑定结构及 Flyway 迁移，保留旧 Cloud ID；实现可重入回填、冲突报告和受控转移。对应 R2、R5。
- [x] T1.3 验证新库、旧库、重复升级、重启/恢复、网关重绑、本地 ID 复用、克隆和身份冲突；升级前后核对原有引用。对应 R2、R8。验收证据见下方 T1.3 进度；现场固件及安装流程属于 T6。

T1.2 进度：Cloud V6 身份结构保留旧 `cloud_printer_projection.id`，将旧映射设为 `LEGACY_UNVERIFIED`，并建立来源/设备唯一键、绑定历史与身份审核表；通过 Flyway 顺序迁移及 H2/MySQL 全量迁移测试。新增农场管理权限下的 legacy identity claim、遗留身份审核列表、同农场受控迁移及身份冲突持久化分页报告。迁移保留 Cloud ID/历史，关闭旧 binding、递增 revision、清理旧当前状态，并等待目标网关完整库存确认；跨农场、来源、UID 和本地 ID 冲突会拒绝并留存冲突原因。Cloud `mvnw verify` 69 项通过，C1/C2 隔离集成 20 项通过；实现分属 Cloud 提交 `55665e6`、`1475092`、`88a4602`、`a13466f`。T1.2 已完成。迁移恢复验收归入 T1.3。

T1.3 进度：Server SQLite 旧库 fixture 校验 source/device UUID 与 execution UUID/首次观测时间回填、重复升级稳定性及既有 Farm 任务外键；Cloud 隔离 MySQL 验证 V1→V16 保留账号/会话/农场、空库初始化和应用重启恢复。真实 EMQX/Paho 验证同农场身份转移、目标 local ID 冲突、本地 ID 复用后创建新 Cloud ID、重复 `(sourceInstanceId, deviceUid)` 与跨网关克隆 source instance 拒绝、网关重绑后 V3 inventory 沿用原 Cloud ID 并递增 binding revision。C1 20 项、Cloud 全量 69 项及 Server SQLite 迁移/回填测试均通过，原账号/会话/农场/任务引用保持。T1.3 完成；未连接现场 Server、真实打印机或验收 Windows 安装流程，这些保留在 T6。

T1.2 追加进度：Cloud 新增 `GET /api/v1/farms/{farmId}/printer-identities/unverified` 遗留身份审核列表、`POST /api/v1/farms/{farmId}/printers/{printerId}/identity/transfer` 同农场受控身份迁移，以及 `GET /api/v1/farms/{farmId}/printers/{printerId}/identity/conflicts` 冲突记录。详情见 Cloud `docs/C2_IMPLEMENTATION.md` 和提交 `55665e6`、`1475092`。Cloud 身份管理和审计交付完成，后续真实迁移/恢复验收列入 T1.3。

T2 进度：canonical 类型、字段质量校验、能力声明、Printer/observation/持久执行映射、SQLite 旧库升级、最新模型恢复以及 Klipper/RRF 合成采集与 LAN 兼容均已实现。执行 ID 稳定且未知终态不会伪装为完成；采集不一致不覆盖已保存观测。事件/告警/遥测数据契约、SQLite 有界事件 outbox、Cloud 历史查询和受控采样策略已实现；执行与监控关闭事件和业务事务原子写入。T2.1–T2.7 的代码/合成测试工作已完成，Server 全量 `mvnw.cmd test` 831 项通过。完整模型 MQTT 发布与提交 ACK 属于 T5，真实固件与安装版验收属于 T6，均未完成。

T2.1 验收记录：`CanonicalPrinterModelMapperTest` 的 6 项测试通过；正例覆盖完整模型映射，反例覆盖错误版本、缺失设备 UID、时间倒置以及质量为 GOOD 但数值缺失。Server 全量 `mvnw.cmd test` 共 816 项通过。真实固件采集属于后续 T2.2/T2.3/T2.7，不包含在此项完成声明中。

T2.5 验收记录：SQLite 集成测试重建状态服务，验证已持久化快照恢复、新 boot ID/观测 revision、稳定的 descriptor revision 及 `CONTINUITY_UNKNOWN`。执行生命周期回归验证 `currentExecution` 可跨服务重建恢复，执行结束后转入 `lastExecutionResult`；未知结果仍为 `UNKNOWN/UNCONFIRMED`。查询时将打印机当前连接健康覆盖到最新持久快照：监控关闭或最近成功采集超过 30 秒为 `UNKNOWN`，明确离线为 `OFFLINE`，同时保留最后测量及其采样时间，已删除设备不返回旧模型。SQLite 集成测试覆盖上述边界；Server 全量 `mvnw.cmd test`（824 项）通过，包含现有缓存、监控、任务/控制、权限和 WebSocket 回归。未连接真实打印机。

T2.6 进度：新增 `CanonicalPrinterEvent` 受限不可变事件对象，与 Cloud V3 事件词汇对齐；校验 UUID/设备身份、事件枚举、严重级别、代码格式和安全摘要上限，拒绝任意固件事件名及控制字符。SQLite `farm_printer_event_outbox` 已加入新库入口和旧库幂等升级，按入队顺序读取；相同事件 UUID 与内容幂等、相同 UUID 不同内容冲突；容量默认 10,000 条且可配置，满时拒绝并明确报错，只有显式 ACK 才删除。监控现于 ONLINE/OFFLINE 健康边界变化时记录 `DEVICE_CONNECTED`/`DEVICE_DISCONNECTED`，监控关闭和执行开始、暂停、恢复、完成/取消/失败及来源切换会关联稳定 execution UUID 记录事件。执行生命周期和监控关闭事件现在在各自业务事务内写入 outbox；outbox 使用当前 SQLite 事务，回滚时事件行一并回滚。容量满或事件写入异常仍被事件记录层捕获，避免改变现场业务结果。SQLite 集成测试验证外层事务回滚同时撤销 outbox 行。监控连接边界事件来自已完成的监控状态更新，不处在同一业务事务。本地容量检查每分钟运行一次，积压达到容量 80% 发出一次警告，降至 60% 以下记录恢复，使用迟滞避免日志刷屏；重复轮询不重复产生连接事件，消息不含通信错误原文。Cloud 告警/遥测模型和有界查询已完成，采样默认关闭且有保留/清理策略。定向 22 项及 Server 全量 `mvnw.cmd test` 831 项通过。T2.6 完成；完整 MQTT 发布、Cloud 提交 ACK 和断网恢复端到端验证归入 T5/T6。

T2.7 验收记录：Klipper/RRF 合成 fixture、测量边界、采集期间任务切换、canonical SQLite 生命周期/恢复及旧 VO/REST/WebSocket 契约已有测试覆盖；字段范围及未支持/未实测信息见 Server `docs/UNIFIED_DEVICE_MODEL.md` 的 T2.7 表。Server 全量 `mvnw.cmd test`（827 项）通过。未连接真实 Klipper/RRF 打印机；真实只读验收保留在 T6.3。

T2.4 进度：现场执行行已有稳定 UUID、来源、首次观测时间和既有 Farm 任务关联。外部执行稳定空闲后无明确终态证据时记为 `UNKNOWN`，明确取消/中止分别保留对应终态；设备重启判定以最近已观测 uptime 为准并兼容旧记录回退到开始 uptime。Farm 执行重启后若仍报告打印中，保留执行会话/设备绑定并将任务置为 `RECONCILING`，后续仅由设备明确终态完成核验。canonical 观测现映射最近一条已结束执行结果，未知结果为 `UNKNOWN/UNCONFIRMED`。回归覆盖同名文件在前一会话结束后获得新 UUID、来源切换关闭旧会话，以及 SQLite 旧库中未知终态的查询/映射；Server 全量 `mvnw.cmd test`（824 项）通过。T2.4 acceptance completed; indistinguishable same-file restarts without a firmware-observable boundary remain intentionally unsegmented; no real printer was connected.

T2.2 验收记录：Moonraker 按对象白名单请求已加载的基础状态、多个 extruder/加热器、风扇、轴及 filament sensor 对象；无动态对象列表时，回退查询只依赖 `webhooks`、`print_stats`、`display_status`，允许缺少独立 extruder/热床。解析测试覆盖多通道与缺失值；对象选择测试覆盖允许类别及无关/超长对象拒绝。Server 全量 `mvnw.cmd test` 共 816 项通过。未连接真实 Klipper 设备。

## T2 Server 完整统一模型与采集

- [x] T2.1 实现设备资产、描述、能力、通道关联、观测帧和测量质量等模型及序列化校验；依 T0 冻结的 Schema 增加正反例。对应 R1、R3。验收：canonical mapper 正例和版本、身份、时间顺序、测量质量反例测试通过。
- [x] T2.2 Klipper/Moonraker 按可用对象发现能力并采集字段矩阵，支持多工具、加热器、风扇、轴、传感器和缺失对象。对应 R3。验收：对象白名单、多通道映射、缺失通道与通用 Klipper 状态回退测试通过。
- [x] T2.3 RRF 按对象模型和可用字段发现能力并采集字段矩阵；多请求期间的执行切换、部分帧及目标温度按冻结语义处理。对应 R3。验收：RRF 客户端读取 state/job/heat/tools 字段矩阵；采集前后复读 state/job 稳定身份，状态、当前工具、文件名/大小或最近文件身份变化时标记 `INCONSISTENT`，operation 置 `UNKNOWN` 且不发布执行摘要；复读失败保留旧状态接口并将 canonical 帧降级为不一致；稳定帧保持 `PARTIAL`。RRF 客户端覆盖活动目标温度和任务摘要，Parser 测试覆盖 OFF/standby/缺失测量语义。Server 全量 `mvnw.cmd test`（818 项）通过。未连接真实 RRF 设备，采集多两次轻量模型查询的现场延迟仍需设备验收。
- [x] T2.4 实现统一执行标识及现场 `farm_printer_execution` 的兼容扩展，处理外部打印、重启、同文件重复、过渡和未知结果。对应 R4、R5。
- [x] T2.5 实现描述/最新观测/执行的持久化、版本和重启恢复；区分当前与最后已知值，保护原有缓存、锁、任务与安全流程。对应 R4、R5。验收：SQLite 新旧库集成测试验证 latest snapshot、revision/boot 代次及当前/最后执行恢复；连接投影测试覆盖在线、离线、监控关闭、观测过期和设备删除；Server 全量 `mvnw.cmd test`（824 项）通过。
- [x] T2.6 增加事件、告警、采样及有界存储/清理所需现场模型；约定断网事件可靠性和状态合并规则。对应 R4。
- [x] T2.7 执行 Klipper/RRF fixture、边界、SQLite 生命周期及旧 LAN API/WebSocket 回归；输出字段覆盖与无法获取的证据。对应 R3、R5、R8。验收：fixture/生命周期/旧契约测试及字段缺口表见 Server `docs/UNIFIED_DEVICE_MODEL.md` T2.7 节；全量 `mvnw.cmd test`（827 项）通过。真实设备仍待 T6.3。

## T3 Cloud 模型、查询与兼容

- [x] T3.1 实现完整设备描述、观测、执行、事件、告警和采样的 Cloud 数据结构、Flyway 增量迁移和事务边界；保留旧列表投影。对应 R1、R4、R7。
- [x] T3.2 使用合成消息实现完整协议消费、模型校验、身份绑定、授权及持久化；支持完整库存分片的组装、限额、去重、过期和提交确认。对应 R6。验收：Cloud canonical 消息测试及 inventory 分片边界测试通过；隔离 MySQL/EMQX 验证 inventory 自动身份绑定和后续 descriptor 接收。
- [x] T3.3 建立完整设备详情、执行/事件/告警/历史的有界只读 API；沿用组织/农场权限和跨范围不可探测约束。对应 R7。
- [x] T3.4 旧 V1、受限 V2、旧列表及现有单设备状态接口兼容；验证旧设备 ID、旧客户端和受限上报不受影响。对应 R6、R7。
- [x] T3.5 运行 Cloud `mvnw verify`，隔离 MySQL 迁移与 MQTT TLS、权限、重复、乱序、重启、分片缺失/超限和回执测试。对应 R6、R8。

T3 进度：Cloud V7 增加 canonical 最新对象兼容列，V8 增加 descriptor 表，V9 增加独立最新 observation 表，V10 增加 execution/result 历史记录，V11 增加 `cloud_printer_event`，V12 增加持久化 inventory 分片 staging 与 latest revision/最终 ACK 记录，V13 增加新库存设备身份注册审计；V14 增加规则版本/告警实例/确认表，V15 增加带质量与 observation 去重的遥测样本表，V16 增加身份冲突报告。Cloud 实现有界结构/质量校验、V3 `descriptor`、`printer-state`、`event` 与分片 `inventory` 消费、当前身份绑定及 descriptor revision 校验、事务提交后复用现有 state-ack、重复消息处理，以及对 observation revision 的旧帧拒绝和同版本冲突检查。网关 ACL 精确允许已实现的 inventory/descriptor/state/event 上行 topic，未实现的 topic 与通配发布仍拒绝。新发现设备由已认证网关事务性注册 source/device UID 和 active binding 并写入审计；legacy 资产仍需管理员认领。currentExecution 与 lastExecutionResult 按 execution UUID 合并持久化，未由实时打印状态推断执行结果。事件按 `(sourceInstanceId,eventId)` 幂等，支持迟到写入且不回退网关 sequence、不覆盖当前 observation；未知事件字段拒绝。设备详情从已验证身份、descriptor 与 observation 表组装 canonical 响应，不再依赖 V7 投影 JSON；已提供按农场/设备授权的执行及事件历史分页 API，执行响应过滤 `localFarmJobId`，事件响应不暴露来源实例或现场诊断。V14/V15 H2 迁移验证通过。2026-11-02 Cloud 全量 `mvnw verify` 69 项通过，覆盖 ArchitectureTest、canonical 状态、身份审核/迁移与分片缺失/超限等集成测试；隔离 C1 20 项通过，验证 MySQL V1→V16 数据保留和空库初始化、真实 EMQX TLS/ACL、Paho 提交回执、网关重绑/克隆冲突/重复/旧启动隔离、吊销与轮换。DAO/公开 VO 分层错误已修复，Cloud 集成测试改用当前真实的 `FarmService.createMember`。Cloud 已支持显式开启时将 GOOD 温度写入 V15、按设备/通道/指标降采样，并按接收时间清理过期样本；默认关闭，默认间隔 15 秒、保留 7 天。T3.1/T3.3 的模型、迁移、事务边界及有界授权查询均已完成；Cloud 自动告警规则评估尚未实现，需作为独立工作项明确规则后再实施。Server V3 publisher、outbox 和协议协商已实现，后续证据见 T5；尚未连接现场 Server/打印机。

T3.3 进度：Cloud 设备 canonical 详情及执行、事件、告警、遥测历史均提供分页/有界查询并执行农场权限检查；遥测时间范围最长 31 天、页面大小限制 1–100，执行查询过滤 `localFarmJobId`，事件响应不暴露来源实例和私有诊断。OPEN 告警支持确认，VIEWER 被拒绝；首次备注作为不可变审计保存，重试幂等返回首次结果。H2 覆盖样本/告警查询、确认审计、跨农场拒绝、超范围时间拒绝与清理，Cloud 全量 69 项验证及隔离 C1 20 项均通过。Flutter 已接入执行、事件、告警、遥测历史分页及告警确认，并处理加载、失败重试和账号会话变化。T3.3 已完成。告警实例的数据模型和确认/查询已完成；自动规则评估与自动创建暂未实现，独立规则参数尚待确定。采样功能默认关闭，现场未启用。

T3.4 进度：新增 Cloud H2 集成测试 `RestrictedTelemetryCompatibilityTest`，同一现场设备先接收 schemaVersion=1 快照，再接收受限 schemaVersion=2 observation；验证旧 Cloud printer ID 与本地 `localPrinterId` 保持不变，通过现有 `/printers` 列表、`/state`、`/executions`、`/events` HTTP API 验证旧投影继续返回、canonicalModel 不伪造、GOOD 零值仍为 0，且旧设备没有 canonical execution/event 时历史页为空。该用例已纳入全量 69 项测试。既有 canonical integration test 也验证 legacy 资产认领后沿用旧 Cloud printer ID；隔离 C1 另有真实 EMQX schemaVersion=1 快照的 ACK、重复和 boot fencing 验证。T3.4 已完成。

T3.5 进度：Cloud 全量 `mvnw verify` 69 项通过，JAR 打包成功；覆盖 ArchitectureTest DAO/Service/VO 边界、H2 V1→V16 迁移、canonical descriptor/state/execution/event、库存分片缺失/超限及最终 ACK、旧 V1/受限 V2 兼容、身份审核与同农场身份转移、重复/乱序和权限。隔离 C1 脚本 20 项通过，包括 MySQL V1→V16 保留数据、V16 空库初始化、真实 EMQX TLS/ACL、Paho 提交回执/去重/旧启动拒绝、MySQL 同农场身份转移/网关重绑保持设备 ID/跨网关克隆冲突/本地 ID 复用/冲突留存/重启恢复及吊销/轮换。相关证据和运行边界见 Cloud `docs/data-model/VERIFICATION.md`；未连接现场 Server/打印机。T3.5 已完成。

T4.1 进度：Flutter Cloud DTO/Repository 已覆盖 V2 canonical descriptor、observation、current execution、last execution result、设备 identity、执行/事件/告警/遥测历史及告警确认。原始 canonical model 递归只读保留，未知枚举保持原字符串；可选身份、测量、数组和历史字段按空/未知处理，未返回 canonicalModel 的旧 Cloud 状态继续按旧字段解析。测试覆盖未知状态、缺失字段、旧响应兼容和执行结果解析。T4.1 已完成。

T4.2 证据：设备列表和详情展示身份、固件、模型版本、能力项、通道观测及执行摘要；Flutter `d7eada4` 在状态不可用时显示带采集时间的上次上报状态，并继续隐藏过期数值，没有采集时间时不虚构历史；`02bd826` 在无完整模型时保留现场设备 ID；`e426fbb` 区分设备当前不在库存与不存在或无权访问。Flutter 全量检查 77 项通过；`2e5d343` 与 Cloud `7c6af07` 以真实隔离 Cloud 响应验证在线及断网展示。Android 手机及真实设备验收继续由 T6 覆盖，不将隔离测试当作现场通过。

Cloud `b5b6715` 在授权农场内区分库存外设备和未知设备，跨农场及未授权访问仍返回通用 404；模拟设备转移或重绑时的库存外状态不等同永久删除。结合真实 HTTP、Server 库存同步和客户端组件测试，隔离 C1/C2 共 43 项通过。

## T4 手机完整模型展示

- [x] T4.1 按冻结的 API 建立类型、Repository 与解析；兼容未知枚举、缺失字段和旧服务版本。对应 R7。
- [x] T4.2 机器列表及详情展示设备描述、能力、当前观测、执行与关联信息；明确实时、过期、离线、不可用和上次已知状态。对应 R7。
- [x] T4.3 接入授权范围内的执行、事件、告警及采样查询和必要交互；处理分页、失败、重试、账号/农场切换与退出。对应 R4、R7。
- [x] T4.4 覆盖小屏、大字、加载、空数据、权限撤销、网络失败及迟到响应；运行 Flutter 静态分析、全量测试及文档检查。对应 R7、R8。

T4.3 进度：手机设备详情页提供历史入口；独立历史页按标签加载执行、事件、告警与最近 24 小时遥测，逐页请求并支持前后翻页和手动刷新。告警 OPEN 时提供确认动作；访问失败显示错误并可重试，403/404 使用不泄露资源存在性的通用提示。页面 VM 绑定登录会话 revision，账号切换或退出时丢弃旧请求结果并清空页面数据。已通过 Flutter 的历史 Repository 测试及告警页面交互测试。遥测历史默认关闭时呈现空状态；规则评估未实现时不会自行生成告警。


## T5 完整模型上报：前置模型完成后实施

- [x] T5.1 核对 T0–T3 前置模型、Schema、存储、Cloud 双读和权限测试通过；未经门禁不得开启完整上报。对应 R6。证据：Cloud V1→V16 迁移、旧 V1/受限 V2 与 canonical V3 双读、Cloud 农场授权等验证通过；Server 的 V3 上报已实现，默认关闭，仍需完成后续资源和现场验收。
- [x] T5.2 实现版本协商、低频库存和描述、单设备观测/执行及事件 outbox；限制大小、并发、带宽、内存与磁盘。对应 R6。

T5.2 证据：Server 已实现协议协商和 V1/V2 回退、有界库存分片及 ACK、描述版本确认、单设备观测和执行上报、SQLite 事件 outbox 和 ACK 后删除；库存变化定期对账，库存版本跨重启持久化，V3 默认关闭。最多 200 台设备、单包 64 KiB、云端读取单台本地模型 128 KiB、单条在途消息和可配置带宽预算；事件 outbox 有数量上限。上云投影去除本地任务标识、私有路径和诊断文本，并限制传感器值类型。Cloud 验证绑定代次、描述版本与观测时效。Server 全量测试 844 项、Cloud 全量验证 73 项通过；隔离 C1 用真实 Server JAR、MySQL 和 EMQX 验证合成模型的描述、观测、执行、事件断网补报与去重，以及库存增删对账（27 项）。单机上限压力、慢网及真实设备分别留在 T5.4 和 T6；当前结果不是现场设备验收。
- [x] T5.3 落实分片全量提交、事务 ACK、幂等去重、重复/乱序/重连、旧绑定隔离和配置回退；空库存只影响对应网关。对应 R6、R8。

T5.3 证据：Cloud 分片 staging、最终提交 ACK、重复事件去重、绑定与启动代次隔离已有测试；Server 的 V1/V2 回退和断线重发已有单测。隔离 C1/C2 使用真实 Server JAR、MySQL 和 EMQX 验证断网事件补报、重复事件、库存增删以及 Server 进程重启后版本递增、Cloud 提交新库存并保持设备 ID（28 项通过，Cloud 提交 `4961993`）。Cloud `b7c3b72` 补充倒序分片、最终 ACK 重发以及跨网关空库存隔离，全量 `verify` 74 项通过。事件重放用重新入队模拟 ACK 丢失，不代表现场断电验收。
- [x] T5.4 验证单机上限、复杂多通道包、慢网和 Cloud 不可用时现场业务持续运行；核对隐私白名单与脱敏。对应 R6、R8。

T5.4 进度：Server `b2503a9` 验证 200 台设备、最长名称库存可分为 8 个有界消息且身份不丢失，第 201 台拒绝；Server 全量 845 项通过。Cloud `33d2314` 的隔离 C1 使用真实 Server JAR、MySQL 和 EMQX 验证 200 台合成设备库存上报及移除后对账。Cloud `0fcf862` 进一步验证双工具、三路加热器、两路风扇、三轴、传感器及零值上报；发现并由 Server `1c8d2e3` 修复 JSON 传感器空值解析问题，C1/C2 30 项通过。带宽预算、单模型读取上限及文件路径/诊断文本脱敏有单测；隔离 C1 已验证 Broker 离线期间本地健康接口可用与事件补报。合成用例不是长时间性能测试；真实网络延迟、丢包及现场业务持续性仍待验证。

Cloud `a6c14e9` 补充应用层 16384 字节/秒预算的隔离测试：200 台库存同步 9.9 秒，期间本地健康检查可用、采样进程最大 RSS 388956 KiB（包含 JVM），C1/C2 30 项通过。令牌桶允许突发且未模拟网络延迟、丢包或长期运行；现场业务持续性仍须验证。

Cloud `caa91cf` 使用临时管理员验证 Broker 中断时本地打印机列表读取和私人文件夹创建，Broker 恢复后事件补报成功，隔离 C1/C2 31 项通过。此结果覆盖所列读写，不代表全部现场业务或真实网络故障已验收。

Cloud `8be766f` 增加隔离 MQTT 双向数据块各 150 毫秒延迟，并结合 16384 字节/秒上报预算、200 台库存、Broker 中断和 Flutter 读取复验；`c8834e4` 进一步同时切断 Server 的 Cloud HTTP 控制面与 Broker，验证本地业务读写、恢复后事件补报及 Flutter 查询；C1/C2 36 项通过。T5.4 为隔离环境验收，未模拟随机丢包、长时间弱网或真实设备；现场验收继续由 T6 承担。
- [x] T5.5 在隔离环境串联真实 Server 构建、Cloud MySQL/EMQX 和客户端，核对完整对象与可用质量，不依赖模拟结果声称现场通过。对应 R6–R8。

T5.5 证据：Cloud `4074630` 配合 Flutter `e4301f5`，使用临时回环转发将 Windows Flutter 测试客户端接入真实 Server JAR、隔离 MySQL 和 EMQX，验证多通道状态、零值/布尔值/不可用质量、列表详情、执行和事件历史以及本地任务标识脱敏。Cloud `898bd5c` 与 Flutter `f9d7d6c` 增加真实上报遥测样本查询、合成告警查询与确认，隔离 C1/C2 34 项通过。测试发现并由 Flutter `5ff8535`、`4036119` 修复布尔/轴数据及未结束执行和无说明事件的解析展示。测试设备和告警为合成数据，Flutter 运行于 Windows 测试进程；自动告警规则未实现，Android 和真实设备验收属于 T6。

## T6 部署和真实设备只读验收

- [x] T6.1 给出 Server SQLite 与 Cloud MySQL 的升级、必要备份、恢复、监控及先 Cloud 后 Server 的兼容发布方案；验证回退。对应 R8。

T6.1 证据：Server `d9f312b` 给出升级顺序、快照范围、关闭 V3 与恢复数据库的差异及监控边界；Cloud `b141862` 验证隔离 MySQL V1 和 SQLite 升级前快照的独立恢复。Cloud `8f61b62` 进一步用本地离线构建的历史 Cloud `66a94ea` 启动恢复库并读取账号、农场，用历史 Server `2729a15` 启动恢复的 SQLite 并通过认证接口读取设备；合并弱网及 Flutter 的 C1/C2 共 40 项通过。历史程序构建跳过其测试；此结果仅证明这组旧程序和对应合成快照，不证明任意旧版可以直接使用升级后的库，也未操作真实环境。
- [x] T6.2 构建并验证 Windows 安装版、Cloud 和手机 App，记录具体版本、配置与开关；真实服务变更前核实目标。对应 R8。

T6.2 构建证据：2026-11-03 基于 Server `f334e44` 构建独立目录 `dist/windows-model-v3-validation/FabMatrix-Server-Setup-1.0.0.exe`，SHA-256 `1B69162334E404250EE3F7C4F9054170FF0980D83799D8C55CB8A82F9A162836`；此前同一代码版本全量 845 项测试通过，打包后独立启动/健康及安装器契约通过。Cloud `verify` 74 项通过并生成 JAR；2026-11-04 Flutter `e426fbb` 常规检查 77 项通过，隔离专用用例另在 C1/C2 运行，Android debug APK SHA-256 `F599DC1EC18CB9CAEBA23320A3C9BDDBC2A7F8A59D2DAC248F458999018671FE`。V3 上报默认关闭，现场已显式启用。

T6.2 实际部署验收（2026-11-04）：经用户授权，Cloud `b5b6715` 从 V5 升级至 V16，升级前一致性快照在独立 MySQL 恢复并由新版 Cloud 升级，用户、组织、农场、网关、设备投影数量保留；正式实例健康。安装版 Server 先停止并保存完整数据快照，对 SQLite 副本试运行新程序成功，再安装 `f334e44` 并检查健康及本地登录。随后在持久配置启用 V3，原网关重连且 Cloud 收到设备数为 0 的 V3 库存。手机 PJE110 保留数据安装 Flutter `e426fbb`，真实 Cloud 登录会话保留，模拟设备列表与详情可见。安装中发现备份/恢复脚本助手作用域错误，Server `d985fd0` 修复并通过隔离维护测试，已同步到已安装维护目录；不将模拟设备视为真实设备。物理设备和全场景手机验收仍分别属于 T6.3、T6.4。

包含维护修复的后续 Windows 安装包位于 `dist/windows-model-v3-maintenance/FabMatrix-Server-Setup-1.0.0.exe`，SHA-256 `32AE532A3DEEB4C95CDF5BB77872B104F6B1EA01CFF70495DFA8929442C47BFE`；该包通过安装器契约和独立启动检查，已安装程序的两个维护脚本经哈希核对同步，未再次运行此安装包。
- [x] T6.3 分别使用一台 Klipper 和一台 RRF 真机做授权只读验收，记录固件、可用能力、设备状态与数据来源；不执行物理动作。对应 R3、R8。

T6.3 证据（2026-11-04）：经用户提供地址并授权，新增一台 RRF 实机到本地 Server，只读模型接口返回 RepRapFirmware 3.7.0、设备空闲、当前工具 0、两个加热器当前约 20℃/22℃ 且目标 0℃；Cloud 收到 V3 库存、descriptor 和 observation，Android 详情显示在线空闲、工具和加热器温度。新增一台 Klipper 真机，只读 Moonraker 返回 ready/standby、Klipper `v0.13.0-700-gd6ea6254`、Moonraker `v0.10.0-31-gd5ee171`，喷嘴约 19℃、热床约 17℃；Server、Cloud 和 Android 显示在线空闲及相符温度。现场发现 Klipper 当前工具未映射，Server `196a0f6` 修复并通过 846 项全量测试；升级安装后 Cloud 和 Android 均显示当前工具 `extruder`。两台设备观测完整度均为 PARTIAL；未发送打印控制命令，未验证打印过程和全部能力，温度为采集时点的值。
- [x] T6.4 验证手机在在线、断网、离线、删除设备、网关重绑及恢复时展示正确；记录限制与未支持字段。对应 R7、R8。

T6.4 部分证据：Android PJE110 通过本地 Cloud 显示 RRF 和 Klipper 真机在线/空闲及加热器；短暂停止空闲的现场 Server 后，两台设备在手机列表显示网关离线、状态未知及带采集时间的上次上报状态；Server 自动启动后手机列表恢复设备在线/空闲。此测试模拟网关中断，并未断开打印机本身。经用户明确授权，现场网关重绑从版本 2 升至 3，新凭据写入受保护目录，两台设备的 Cloud ID 保持不变，库存恢复两台且手机再次显示在线空闲。

T6.4 补充证据：经授权新建并删除单独的虚拟设备记录，手机停留在详情时显示设备不在库存，Cloud 库存恢复两台真机，保留移除投影和操作审计；未删除真机。断开手机的 Cloud 转发并重启 App 后，已保存登录无法在线验证；Flutter `b490402` 改为显示网络验证失败并提供就地重试，恢复转发后无需输入密码即可恢复登录。RRF 真机实际断开时 Server 判为离线、Cloud 观测过期，发现旧客户端会优先显示过期；Cloud `995d71e` 与 Flutter `c1f4782` 区分连接状态时效和观测时效，更新后经用户再次关机复验：手机显示设备离线且旧温度隐藏；用户重新开机后设备和手机恢复在线。Flutter `f566cc8` 修正过期或 canonical 数据误显示旧协议提示，已安装并验证。

现场发现能力检测时间使描述版本随轮询递增，Server `1d7f77f` 保留能力首次确认时间，只有实际描述变化才升版；846 项全量测试、安装器契约及两台真机多轮采样验证通过。Cloud、Server、Flutter 的隔离 C1/C2 共 44 项通过；虚拟设备和自动化环境不替代实体打印过程验收。

最新 Windows 安装包为 `dist/windows-model-v3-stable-descriptor/FabMatrix-Server-Setup-1.0.0.exe`（SHA-256 `32EFE139F9355EE67374E26E49DCEEB76E03891865ABB352B98EF75325D20CD8`），已安装；手机安装 Flutter `f566cc8` 的 debug APK（SHA-256 `D72ECC11D923B6111BCB7EA00D7B794994C036EBD81379CD1CC7CF85A18EDD13`）。两项均针对本机开发环境，不代表公网发布。
- [ ] T6.5 汇总字段覆盖、测试与现场证据、协议兼容、性能和遗留项；只有满足 `requirements.md` 全部完成定义才标记本规格完成。对应 R1–R8。
