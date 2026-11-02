# 统一设备模型完整落地 Kiro 任务

状态：进行中；创建日期：2026-11-02。任务属于跨仓库只读模型建设，先模型、后完整上报。勾选必须附实际证据。需求和约束见同目录 `requirements.md`、`design.md`。

## T0 基线、范围和契约冻结

- [x] T0.1 盘点 Server、Cloud、Flutter 的现有实体、协议字段、调用关系、持久化、已部署版本与未提交修改；区分已实现、设计和缺口。对应 R1、R5、R6。证据：同目录 `baseline.md`；Cloud 未提交改动按用户工作区保留，未部署状态未臆断。
- [x] T0.2 审核现有完整模型、固件映射和 Schema，建立逐字段矩阵：来源、单位、质量、可空性、授权、持久化、上报及验收；定义完整交付范围，不把未知字段伪装为已支持。对应 R1、R3。证据：同目录 `baseline.md` 的 canonical 字段级映射及各仓库当前覆盖；固件真实设备覆盖仍需 T6 验收。
- [x] T0.3 冻结来源实例、设备 UID、Cloud 设备 ID、网关重绑/转移与克隆冲突决策，以及旧数据的映射、回填、恢复和审核策略。对应 R2。证据：Cloud ADR 0010；旧映射不按本地 ID 猜测，克隆分叉和管理员认领规则已明确。
- [x] T0.4 冻结完整协议版本/协商、字段兼容、分片/ACK/去重、限额和回退；既有 V1、受限 V2 不改变含义。对应 R1、R6。证据：Cloud ADR 0010 与更新后的协议/存储路线文档；canonical modelVersion=2，完整 transport schemaVersion=3，实际支持仍待实施。
- [x] T0.5 冻结事件、告警、采样保留和权限边界，以及本轮设备模型与远程控制、视频、文件传输的接口边界。对应 R1、R4、R7。证据：Cloud ADR 0010；告警由 Cloud 规则派生，事件需持久 outbox/提交 ACK，未批准的阈值/保留期不写死，本期不含控制/媒体。
- [x] T0.6 更新跨仓库事实文档和 ADR，清理与当前 SQLite、受限 V2 和独立详情接口不一致的说法；记录尚待用户确认的重大决策。对应 R1、R8。证据：本规格 `baseline.md`、Server `docs/UNIFIED_DEVICE_MODEL.md`、Cloud 模型/协议文档及 ADR 0010；Cloud 未提交文件仍按用户工作区保留。

## T1 身份和数据迁移基础

- [x] T1.1 Server 为来源实例和设备增加持久稳定 ID；实现已有 SQLite 增量升级与全新库初始化，兼容现有数字 ID 及业务关系。对应 R2、R5。证据：`farm_installation_identity` 单例、`farm_printer.device_uid` 唯一索引、旧库自动回填、服务新增设备生成 UUID；`LocalSchemaUpgradeIntegrationTest` 覆盖既有任务引用、重复迁移稳定性。
- [ ] T1.2 Cloud 新增必要的身份、来源与绑定结构及 Flyway 迁移，保留旧 Cloud ID；实现可重入回填、冲突报告和受控转移。对应 R2、R5。
- [ ] T1.3 验证新库、旧库、重复升级、重启/恢复、网关重绑、本地 ID 复用、克隆和身份冲突；升级前后核对原有引用。对应 R2、R8。

T1.2 进度：Cloud V6 加法迁移保留旧 `cloud_printer_projection.id`，将旧映射设为 `LEGACY_UNVERIFIED`，并建来源/设备唯一键、绑定历史与身份审核表；迁移检查通过。新增农场管理权限下的显式 legacy identity claim Service/API，校验 source/device 唯一性、绑定来源和审计，重复相同认领幂等；定向 Service 测试通过。V3 身份消息接入、跨来源转移、可查询冲突报告及完整 Cloud 迁移回滚仍未实现，故 T1.2、T1.3 不勾选。Cloud V6/V5 相关文件处于用户已有未提交工作区，尚未提交。

T1.3 进度：Server SQLite 旧库 fixture 校验 source/device UUID 回填、重跑稳定性及原任务外键；本轮另验证旧执行记录 `execution_uid` / `first_observed_at` 幂等回填。Cloud 身份绑定迁移已做隔离集成测试；真实网关重绑、克隆分叉和 Cloud 管理员认领尚未实现，因此 T1.3 不勾选。

T1.2 追加进度：Cloud 已增加 V3 `printer-state` 消费前置身份认领 API，旧 Cloud 设备须由有农场管理权限的用户显式认领来源实例和 device UID，并保留既有 Cloud printer ID；Cloud V7 存储最新 canonical 对象，V3 接收必须匹配当前网关绑定。迁移和认领流程已有 H2 合成测试，尚未在 MySQL/真实网关迁移验证；跨来源设备转移和冲突报告未实现，因此仍不勾选 T1.2。

T2 进度：新增 canonical 记录类型、结构引用/质量校验、有限能力声明和 Printer + observation + 持久执行记录映射；SQLite 旧库升级增加执行 UUID/首次观测时间；本地监控将最新模型写入 SQLite，并保存模型进程代次、观测 revision 和 descriptor revision。Canonical 状态服务现可按设备 ID 读取并重新校验最新完整模型；Cloud 采集边界新增隐私过滤的 canonical snapshot，保留设备稳定身份、描述摘要和可空的已持久化完整模型，不改变现有 V1/V2 发送路径。SQLite 集成测试覆盖首次无记录、持久化后读取身份/版本/revision；Cloud 采集测试覆盖缺少 canonical 观测时仍保留资产。执行 ID 来自持久本地记录，外部执行不伪造开始时间；不一致观测不覆盖已保存值。Klipper 当前按 Moonraker 已加载对象白名单采集多加热器、风扇、轴和耗材传感器；RRF 解析响应中提供的风扇、轴和耗材监测器，并通过 `flags=f` 请求实时状态对象字段。动态能力完整性、固件版本/连接模式、RRF 多请求任务切换检测、终态结果映射、事件 outbox、Cloud V3 上报及 LAN 完整模型读取仍未完成，T2.1、T2.2 已完成；T2.3–T2.7 均不勾选。全量 `mvnw.cmd test` 816 项通过。真实固件与安装版未验证。

T2.1 验收记录：`CanonicalPrinterModelMapperTest` 的 6 项测试通过；正例覆盖完整模型映射，反例覆盖错误版本、缺失设备 UID、时间倒置以及质量为 GOOD 但数值缺失。Server 全量 `mvnw.cmd test` 共 816 项通过。真实固件采集属于后续 T2.2/T2.3/T2.7，不包含在此项完成声明中。

T2.5 验收记录：SQLite 集成测试重建状态服务，验证已持久化快照恢复、新 boot ID/观测 revision、稳定的 descriptor revision 及 `CONTINUITY_UNKNOWN`。执行生命周期回归验证 `currentExecution` 可跨服务重建恢复，执行结束后转入 `lastExecutionResult`；未知结果仍为 `UNKNOWN/UNCONFIRMED`。查询时将打印机当前连接健康覆盖到最新持久快照：监控关闭或最近成功采集超过 30 秒为 `UNKNOWN`，明确离线为 `OFFLINE`，同时保留最后测量及其采样时间，已删除设备不返回旧模型。SQLite 集成测试覆盖上述边界；Server 全量 `mvnw.cmd test`（824 项）通过，包含现有缓存、监控、任务/控制、权限和 WebSocket 回归。未连接真实打印机。

T2.6 进度：新增 `CanonicalPrinterEvent` 受限不可变事件对象，与 Cloud V3 事件词汇对齐；校验 UUID/设备身份、事件枚举、严重级别、代码格式和安全摘要上限，拒绝任意固件事件名及控制字符。SQLite `farm_printer_event_outbox` 已加入新库入口和旧库幂等升级，按入队顺序读取；相同事件 UUID 与内容幂等、相同 UUID 不同内容冲突；容量默认 10,000 条且可配置，满时拒绝并明确报错，只有显式 ACK 才删除。监控现于 ONLINE/OFFLINE 健康边界变化时记录 `DEVICE_CONNECTED`/`DEVICE_DISCONNECTED`，监控配置事务提交后记录 `MONITOR_DISABLED`；执行服务对开始、暂停、恢复、完成/取消/失败及来源切换生成事件，并关联稳定 execution UUID。执行事件在业务事务提交后写入 outbox，避免 outbox 失败回滚现场任务；但提交与 outbox 插入之间存在进程崩溃窗口，目前不构成原子事务 outbox。本地容量检查每分钟运行一次，积压达到容量 80% 发出一次警告，降至 60% 以下记录恢复，使用迟滞避免日志刷屏。重复轮询不重复产生连接/配置事件；消息不含通信错误原文。旧库迁移、outbox 行为、连接/配置/执行事件及队列告警测试和 Server 全量 `mvnw.cmd test`（831 项）通过。Cloud 发送与 ACK 集成、Cloud 告警模型/API、遥测采样策略及断网/恢复实链路验收仍未完成，T2.6 不勾选。

T2.7 验收记录：Klipper/RRF 合成 fixture、测量边界、采集期间任务切换、canonical SQLite 生命周期/恢复及旧 VO/REST/WebSocket 契约已有测试覆盖；字段范围及未支持/未实测信息见 Server `docs/UNIFIED_DEVICE_MODEL.md` 的 T2.7 表。Server 全量 `mvnw.cmd test`（827 项）通过。未连接真实 Klipper/RRF 打印机；真实只读验收保留在 T6.3。

T2.4 进度：现场执行行已有稳定 UUID、来源、首次观测时间和既有 Farm 任务关联。外部执行稳定空闲后无明确终态证据时记为 `UNKNOWN`，明确取消/中止分别保留对应终态；设备重启判定以最近已观测 uptime 为准并兼容旧记录回退到开始 uptime。Farm 执行重启后若仍报告打印中，保留执行会话/设备绑定并将任务置为 `RECONCILING`，后续仅由设备明确终态完成核验。canonical 观测现映射最近一条已结束执行结果，未知结果为 `UNKNOWN/UNCONFIRMED`。回归覆盖同名文件在前一会话结束后获得新 UUID、来源切换关闭旧会话，以及 SQLite 旧库中未知终态的查询/映射；Server 全量 `mvnw.cmd test`（824 项）通过。T2.4 acceptance completed; indistinguishable same-file restarts without a firmware-observable boundary remain intentionally unsegmented; no real printer was connected.

T2.2 验收记录：Moonraker 按对象白名单请求已加载的基础状态、多个 extruder/加热器、风扇、轴及 filament sensor 对象；无动态对象列表时，回退查询只依赖 `webhooks`、`print_stats`、`display_status`，允许缺少独立 extruder/热床。解析测试覆盖多通道与缺失值；对象选择测试覆盖允许类别及无关/超长对象拒绝。Server 全量 `mvnw.cmd test` 共 816 项通过。未连接真实 Klipper 设备。

## T2 Server 完整统一模型与采集

- [x] T2.1 实现设备资产、描述、能力、通道关联、观测帧和测量质量等模型及序列化校验；依 T0 冻结的 Schema 增加正反例。对应 R1、R3。验收：canonical mapper 正例和版本、身份、时间顺序、测量质量反例测试通过。
- [x] T2.2 Klipper/Moonraker 按可用对象发现能力并采集字段矩阵，支持多工具、加热器、风扇、轴、传感器和缺失对象。对应 R3。验收：对象白名单、多通道映射、缺失通道与通用 Klipper 状态回退测试通过。
- [x] T2.3 RRF 按对象模型和可用字段发现能力并采集字段矩阵；多请求期间的执行切换、部分帧及目标温度按冻结语义处理。对应 R3。验收：RRF 客户端读取 state/job/heat/tools 字段矩阵；采集前后复读 state/job 稳定身份，状态、当前工具、文件名/大小或最近文件身份变化时标记 `INCONSISTENT`，operation 置 `UNKNOWN` 且不发布执行摘要；复读失败保留旧状态接口并将 canonical 帧降级为不一致；稳定帧保持 `PARTIAL`。RRF 客户端覆盖活动目标温度和任务摘要，Parser 测试覆盖 OFF/standby/缺失测量语义。Server 全量 `mvnw.cmd test`（818 项）通过。未连接真实 RRF 设备，采集多两次轻量模型查询的现场延迟仍需设备验收。
- [x] T2.4 实现统一执行标识及现场 `farm_printer_execution` 的兼容扩展，处理外部打印、重启、同文件重复、过渡和未知结果。对应 R4、R5。
- [x] T2.5 实现描述/最新观测/执行的持久化、版本和重启恢复；区分当前与最后已知值，保护原有缓存、锁、任务与安全流程。对应 R4、R5。验收：SQLite 新旧库集成测试验证 latest snapshot、revision/boot 代次及当前/最后执行恢复；连接投影测试覆盖在线、离线、监控关闭、观测过期和设备删除；Server 全量 `mvnw.cmd test`（824 项）通过。
- [ ] T2.6 增加事件、告警、采样及有界存储/清理所需现场模型；约定断网事件可靠性和状态合并规则。对应 R4。
- [x] T2.7 执行 Klipper/RRF fixture、边界、SQLite 生命周期及旧 LAN API/WebSocket 回归；输出字段覆盖与无法获取的证据。对应 R3、R5、R8。验收：fixture/生命周期/旧契约测试及字段缺口表见 Server `docs/UNIFIED_DEVICE_MODEL.md` T2.7 节；全量 `mvnw.cmd test`（827 项）通过。真实设备仍待 T6.3。

## T3 Cloud 模型、查询与兼容

- [ ] T3.1 实现完整设备描述、观测、执行、事件、告警和采样的 Cloud 数据结构、Flyway 增量迁移和事务边界；保留旧列表投影。对应 R1、R4、R7。
- [x] T3.2 使用合成消息实现完整协议消费、模型校验、身份绑定、授权及持久化；支持完整库存分片的组装、限额、去重、过期和提交确认。对应 R6。验收：Cloud canonical 消息测试及 inventory 分片边界测试通过；隔离 MySQL/EMQX 验证 inventory 自动身份绑定和后续 descriptor 接收。
- [ ] T3.3 建立完整设备详情、执行/事件/告警/历史的有界只读 API；沿用组织/农场权限和跨范围不可探测约束。对应 R7。
- [x] T3.4 旧 V1、受限 V2、旧列表及现有单设备状态接口兼容；验证旧设备 ID、旧客户端和受限上报不受影响。对应 R6、R7。
- [ ] T3.5 运行 Cloud `mvnw verify`，隔离 MySQL 迁移与 MQTT TLS、权限、重复、乱序、重启、分片缺失/超限和回执测试。对应 R6、R8。

T3 进度：Cloud V7 增加 canonical 最新对象兼容列，V8 增加 descriptor 表，V9 增加独立最新 observation 表，V10 增加 execution/result 历史记录，V11 增加 `cloud_printer_event`，V12 增加持久化 inventory 分片 staging 与 latest revision/最终 ACK 记录，V13 增加新库存设备身份注册审计；V14 增加规则版本/告警实例/确认表，V15 增加带质量与 observation 去重的遥测样本表。Cloud 实现有界结构/质量校验、V3 `descriptor`、`printer-state`、`event` 与分片 `inventory` 消费、当前身份绑定及 descriptor revision 校验、事务提交后复用现有 state-ack、重复消息处理，以及对 observation revision 的旧帧拒绝和同版本冲突检查。网关 ACL 精确允许已实现的 inventory/descriptor/state/event 上行 topic，未实现的 topic 与通配发布仍拒绝。新发现设备由已认证网关事务性注册 source/device UID 和 active binding 并写入审计；legacy 资产仍需管理员认领。currentExecution 与 lastExecutionResult 按 execution UUID 合并持久化，未由实时打印状态推断执行结果。事件按 `(sourceInstanceId,eventId)` 幂等，支持迟到写入且不回退网关 sequence、不覆盖当前 observation；未知事件字段拒绝。设备详情从已验证身份、descriptor 与 observation 表组装 canonical 响应，不再依赖 V7 投影 JSON；已提供按农场/设备授权的执行及事件历史分页 API，执行响应过滤 `localFarmJobId`，事件响应不暴露来源实例或现场诊断。V14/V15 H2 迁移验证通过；隔离 C1 更新后通过 MySQL V1→V15 升级、空库 V15 初始化及 EMQX TLS/ACL/消息验收。Cloud 过滤版 `mvnw verify` 排除因账号管理分支缺少 `OrganizationMemberService` 的 `TelemetryIntegrationTest` 和期待缺失实现的 `ArchitectureTest` 后，46 项通过；不能记作未过滤全套测试通过。Cloud 已支持显式开启时将 GOOD 温度写入 V15、按设备/通道/指标降采样，并按接收时间清理过期样本；默认关闭，默认间隔 15 秒、保留 7 天。T3.1 仍因告警规则评估和自动创建缺少已批准的规则参数而未完成；T3.3 的 Cloud 遥测/告警历史 API 与告警确认 API 已完成并接入 Flutter 历史页，但自动规则评估和告警创建仍未实现；T3.5 未过滤 Cloud 验收仍未完成。Server V3 publisher/outbox/协商尚未实现。未连接现场 Server/打印机。

T3.3 进度：Cloud 已增加 `GET /api/v1/farms/{farmId}/printers/{printerId}/telemetry`，按农场和设备授权，要求 epoch 毫秒 `from`/`to` 且最长 31 天，支持 channel/metric 过滤和 1–100 条分页；另增加告警历史分页及 OPEN 告警单次确认接口。OWNER/ADMIN/OPERATOR 可确认，VIEWER 拒绝；首次备注作为不可变审计保存，重试幂等返回首次结果。Cloud H2 覆盖样本/告警查询、确认审计、岗位拒绝、跨农场拒绝、超范围时间拒绝与样本过期清理；Cloud 过滤版 verify 46 项通过，C1 隔离 MySQL/EMQX 集成通过。Flutter 已接入执行、事件、告警、遥测历史分页及告警确认，并处理加载、失败重试和账号会话变化；针对 API 和告警页面交互的测试通过。自动规则评估与告警创建受未批准的阈值/规则参数约束，尚未实现，因此 T3.3 仍不勾选。采样功能默认关闭，现场未启用。

T3.4 进度：新增 Cloud H2 集成测试 `RestrictedTelemetryCompatibilityTest`，同一现场设备先接收 schemaVersion=1 快照，再接收受限 schemaVersion=2 observation；验证旧 Cloud printer ID 与本地 `localPrinterId` 保持不变，通过现有 `/printers` 列表、`/state`、`/executions`、`/events` HTTP API 验证旧投影继续返回、canonicalModel 不伪造、GOOD 零值仍为 0，且旧设备没有 canonical execution/event 时历史页为空。新测试通过；过滤版 `mvnw verify` 共 46 项通过。既有 canonical integration test 也验证 legacy 资产认领后沿用旧 Cloud printer ID；C1 另有真实 EMQX schemaVersion=1 快照的 ACK、重复和 boot fencing 验证。T3.4 已完成。未过滤构建因现有账号管理服务缺失问题仍不通过，记录在 T3.5。

T4.1 进度：Flutter Cloud DTO/Repository 已覆盖 V2 canonical descriptor、observation、current execution、last execution result、设备 identity、执行/事件/告警/遥测历史及告警确认。原始 canonical model 递归只读保留，未知枚举保持原字符串；可选身份、测量、数组和历史字段按空/未知处理，未返回 canonicalModel 的旧 Cloud 状态继续按旧字段解析。测试覆盖未知状态、缺失字段、旧响应兼容和执行结果解析。T4.1 已完成。

T4.2 进度：设备详情页展示稳定设备 UID、本地设备 ID、descriptor/observation revision、固件/位置/材料/喷嘴、能力、通道观测、当前执行与上次执行结果，并沿用 freshness 条件隐藏过期数值。机器列表已有 Cloud 农场/设备查询、设备状态、分页和详情导航的工作区实现，但其改动尚未并入已提交版本；列表摘要目前仍未完整展示 canonical identity、capability、execution 与 revision。未连接运行中的 Cloud 或手机真机，因此 T4.2 保持未完成。

## T4 手机完整模型展示

- [x] T4.1 按冻结的 API 建立类型、Repository 与解析；兼容未知枚举、缺失字段和旧服务版本。对应 R7。
- [ ] T4.2 机器列表及详情展示设备描述、能力、当前观测、执行与关联信息；明确实时、过期、离线、不可用和上次已知状态。对应 R7。
- [x] T4.3 接入授权范围内的执行、事件、告警及采样查询和必要交互；处理分页、失败、重试、账号/农场切换与退出。对应 R4、R7。
- [x] T4.4 覆盖小屏、大字、加载、空数据、权限撤销、网络失败及迟到响应；运行 Flutter 静态分析、全量测试及文档检查。对应 R7、R8。

T4.3 进度：手机设备详情页提供历史入口；独立历史页按标签加载执行、事件、告警与最近 24 小时遥测，逐页请求并支持前后翻页和手动刷新。告警 OPEN 时提供确认动作；访问失败显示错误并可重试，403/404 使用不泄露资源存在性的通用提示。页面 VM 绑定登录会话 revision，账号切换或退出时丢弃旧请求结果并清空页面数据。已通过 Flutter 的历史 Repository 测试及告警页面交互测试。遥测历史默认关闭时呈现空状态；规则评估未实现时不会自行生成告警。


## T5 完整模型上报：前置模型完成后实施

- [ ] T5.1 核对 T0–T3 前置模型、Schema、存储、Cloud 双读和权限测试通过；未经门禁不得开启完整上报。对应 R6。
- [ ] T5.2 实现版本协商、低频库存和描述、单设备观测/执行及事件 outbox；限制大小、并发、带宽、内存与磁盘。对应 R6。
- [ ] T5.3 落实分片全量提交、事务 ACK、幂等去重、重复/乱序/重连、旧绑定隔离和配置回退；空库存只影响对应网关。对应 R6、R8。
- [ ] T5.4 验证单机上限、复杂多通道包、慢网和 Cloud 不可用时现场业务持续运行；核对隐私白名单与脱敏。对应 R6、R8。
- [ ] T5.5 在隔离环境串联真实 Server 构建、Cloud MySQL/EMQX 和客户端，核对完整对象与可用质量，不依赖模拟结果声称现场通过。对应 R6–R8。

## T6 部署和真实设备只读验收

- [ ] T6.1 给出 Server SQLite 与 Cloud MySQL 的升级、必要备份、恢复、监控及先 Cloud 后 Server 的兼容发布方案；验证回退。对应 R8。
- [ ] T6.2 构建并验证 Windows 安装版、Cloud 和手机 App，记录具体版本、配置与开关；真实服务变更前核实目标。对应 R8。
- [ ] T6.3 分别使用一台 Klipper 和一台 RRF 真机做授权只读验收，记录固件、可用能力、设备状态与数据来源；不执行物理动作。对应 R3、R8。
- [ ] T6.4 验证手机在在线、断网、离线、删除设备、网关重绑及恢复时展示正确；记录限制与未支持字段。对应 R7、R8。
- [ ] T6.5 汇总字段覆盖、测试与现场证据、协议兼容、性能和遗留项；只有满足 `requirements.md` 全部完成定义才标记本规格完成。对应 R1–R8。
