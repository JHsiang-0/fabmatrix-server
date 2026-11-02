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

T2 进度：新增 canonical 记录类型、结构引用/质量校验、有限能力声明和 Printer + observation + 持久执行记录映射；SQLite 旧库升级增加执行 UUID/首次观测时间；本地监控将最新模型写入 SQLite，并保存模型进程代次、观测 revision 和 descriptor revision。执行 ID 来自持久本地记录，外部执行不伪造开始时间；不一致观测不覆盖已保存值。Klipper 当前按 Moonraker 已加载对象白名单采集多加热器、风扇、轴和耗材传感器；RRF 解析响应中提供的风扇、轴和耗材监测器，并通过 `flags=f` 请求实时状态对象字段。动态能力完整性、固件版本/连接模式、RRF 多请求任务切换检测、终态结果映射、事件 outbox、LAN/Cloud 读取接线仍未完成，T2.1–T2.7 均不勾选。定向测试 30 项通过；全量 `mvnw.cmd test` 812 项通过。真实固件与安装版未验证。

## T2 Server 完整统一模型与采集

- [ ] T2.1 实现设备资产、描述、能力、通道关联、观测帧和测量质量等模型及序列化校验；依 T0 冻结的 Schema 增加正反例。对应 R1、R3。
- [ ] T2.2 Klipper/Moonraker 按可用对象发现能力并采集字段矩阵，支持多工具、加热器、风扇、轴、传感器和缺失对象。对应 R3。
- [ ] T2.3 RRF 按对象模型和可用字段发现能力并采集字段矩阵；多请求期间的执行切换、部分帧及目标温度按冻结语义处理。对应 R3。
- [ ] T2.4 实现统一执行标识及现场 `farm_printer_execution` 的兼容扩展，处理外部打印、重启、同文件重复、过渡和未知结果。对应 R4、R5。
- [ ] T2.5 实现描述/最新观测/执行的持久化、版本和重启恢复；区分当前与最后已知值，保护原有缓存、锁、任务与安全流程。对应 R4、R5。
- [ ] T2.6 增加事件、告警、采样及有界存储/清理所需现场模型；约定断网事件可靠性和状态合并规则。对应 R4。
- [ ] T2.7 执行 Klipper/RRF fixture、边界、SQLite 生命周期及旧 LAN API/WebSocket 回归；输出字段覆盖与无法获取的证据。对应 R3、R5、R8。

## T3 Cloud 模型、查询与兼容

- [ ] T3.1 实现完整设备描述、观测、执行、事件、告警和采样的 Cloud 数据结构、Flyway 增量迁移和事务边界；保留旧列表投影。对应 R1、R4、R7。
- [ ] T3.2 使用合成消息实现完整协议消费、模型校验、身份绑定、授权及持久化；支持完整库存分片的组装、限额、去重、过期和提交确认。对应 R6。
- [ ] T3.3 建立完整设备详情、执行/事件/告警/历史的有界只读 API；沿用组织/农场权限和跨范围不可探测约束。对应 R7。
- [ ] T3.4 旧 V1、受限 V2、旧列表及现有单设备状态接口兼容；验证旧设备 ID、旧客户端和受限上报不受影响。对应 R6、R7。
- [ ] T3.5 运行 Cloud `mvnw verify`，隔离 MySQL 迁移与 MQTT TLS、权限、重复、乱序、重启、分片缺失/超限和回执测试。对应 R6、R8。

T3 进度：Cloud V7 增加 canonical 最新对象兼容列，V8 增加 descriptor 表，V9 增加独立最新 observation 表，V10 增加 execution/result 历史记录，V11 增加 `cloud_printer_event`；Cloud 实现有界结构/质量校验、V3 `descriptor`、`printer-state` 与 `event` 消费、当前身份绑定及 descriptor revision 校验、事务提交后复用现有 state-ack、重复消息处理，以及对 observation revision 的旧帧拒绝和同版本冲突检查。网关 ACL 精确允许已实现的 descriptor/state/event 上行 topic，inventory 和通配发布仍拒绝。currentExecution 与 lastExecutionResult 按 execution UUID 合并持久化，未由实时打印状态推断执行结果。事件按 `(sourceInstanceId,eventId)` 幂等，支持迟到写入且不回退网关 sequence、不覆盖当前 observation；未知事件字段拒绝。设备详情从已验证身份、descriptor 与 observation 表组装 canonical 响应，不再依赖 V7 投影 JSON；已提供按农场/设备授权的执行及事件历史分页 API，执行响应过滤 `localFarmJobId`，事件响应不暴露来源实例或现场诊断。Cloud `mvnw.cmd clean verify`（66 项）通过，覆盖事件分页/认证/农场隔离；隔离 C1 集成脚本副本验证 MySQL V1→V11、24 表空库初始化和 MQTT TLS/ACL，精确验证 V3 descriptor/state/event 可发布且 inventory 不可发布，未通过 MQTT 发送 V3 event。事件接收由 H2 合成消息覆盖，MySQL 验证迁移；未连接现场 Server/打印机。仍缺 inventory 接收、分片和 V3 ACK 契约、告警/采样存储与查询；Server V3 publisher/outbox/协商尚未实现，T3.1–T3.5 继续保持未勾选。

T4.1–T4.2 进度：Flutter 新增 canonical modelVersion 2 的 Cloud DTO 解析和手机机器详情展示（描述、能力、通道测量、执行摘要），继续兼容旧状态与受限 observation。`flutter analyze` 通过，针对解析和设备详情的 3 项测试通过；没有运行中的调试 App，尚未热重载或真机验收。完整事件/历史查询未提供，T4.1–T4.4 均不勾选。

## T4 手机完整模型展示

- [ ] T4.1 按冻结的 API 建立类型、Repository 与解析；兼容未知枚举、缺失字段和旧服务版本。对应 R7。
- [ ] T4.2 机器列表及详情展示设备描述、能力、当前观测、执行与关联信息；明确实时、过期、离线、不可用和上次已知状态。对应 R7。
- [ ] T4.3 接入授权范围内的执行、事件、告警及采样查询和必要交互；处理分页、失败、重试、账号/农场切换与退出。对应 R4、R7。
- [ ] T4.4 覆盖小屏、大字、加载、空数据、权限撤销、网络失败及迟到响应；运行 Flutter 静态分析、全量测试及文档检查。对应 R7、R8。

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
