# Farm v2 Kiro 执行任务

本清单以根目录 `TODO.md` 和 `requirements.md` 为准。旧 `.kiro/specs/farm-v1/` 只记录历史基础能力与验收证据，不在本清单中重复执行。

状态：`[ ]` 未开始，`[~]` 进行中，`[x]` 已完成，`[-]` 明确裁剪。

任务类型约定：`[改造]` 表示在现有接口、Service、定时任务或协议适配器上调整；`[新增]` 表示当前代码中没有对应接口或领域能力；`[测试/文档]` 表示不改变接口地址，补充验证和契约。v2 不是把所有任务接口推倒重做，而是在保留现有单任务接口的基础上增加批量能力，并改造现有状态处理。

当前已经存在、原则上继续兼容的单任务接口：

| 当前接口 | 当前实现位置 | v2 处理 |
|---|---|---|
| `POST /api/v1/print-files/upload` | `PrintFileController`、`PrintFileServiceImpl` | 改造校验/幂等后保留 |
| `POST /api/v1/print-jobs`、`POST /api/v1/print-jobs/create` | `PrintJobController`、`PrintJobServiceImpl` | 统一任务状态后保留 |
| `POST /api/v1/print-jobs/safe/assign` | `PrintJobController`、`PrintJobServiceImpl` | 改造并发占用和权限后保留 |
| `POST /api/v1/print-jobs/safe/confirm` | `PrintJobController`、`PrintJobServiceImpl` | 改造安全确认状态后保留 |
| `POST /api/v1/print-jobs/safe/start` | `PrintJobController`、`PrintJobServiceImpl` | 保留，作为批量启动前的单项安全动作 |
| `POST /api/v1/print-jobs/{jobId}/assign` | `PrintJobController`、`PrintJobServiceImpl` | 已有的直接派发/启动入口，需收敛安全语义；前端默认不使用 |
| `POST /api/v1/control/{id}/pause`、`resume`、`cancel`、`emergency-stop` | `PrinterControlController`、`PrinterControlServiceImpl` | 改造状态回写和设备确认后保留 |

当前代码中没有、需要新增的主要接口是 `batch-upload`、`batch/preview`、`batch/confirm` 以及后台派单设置/计划接口。下面每个 Task 的标题会明确标识类型。

## T0 基线确认

- [x] T0.1 确认产品是局域网本地服务端，角色只保留 ADMIN/OPERATOR。
- [x] T0.2 确认真实 RRF 验收设备为 `192.168.0.77`（Farm ID `564`），`192.168.0.62` 不作为真实设备验收目标。
- [x] T0.3 阅读当前 Controller、Service、协议适配器、任务定时器、配置和 `API_HANDOFF.md`，确认 v1 基础接口可作为兼容层保留。
- [x] T0.4 确认默认业务顺序：单任务手动操作 → 用户确认的批量操作 → 可选后台自动派单。

## T1 [改造 + 新增] 冻结 v2 业务和数据契约

**对应需求：REQ-01、REQ-02、REQ-03、REQ-04、REQ-05、REQ-10**

- [ ] T1.1 将 `MANUAL`、`USER_BATCH`、`BACKGROUND_AUTO`、`UPLOAD_ONLY`、`QUEUE`、`START_AFTER_CONFIRM` 写入统一领域枚举或契约常量。
- [ ] T1.2 统一 `PENDING`、`QUEUED`、`ASSIGNED`、`UPLOADING`、`PRINTING`、`PAUSED`、`RECONCILING`、`COMPLETED`、`CANCELLED`、`FAILED` 的迁移规则，解决当前创建任务与调度扫描状态不一致。
- [ ] T1.3 定义批量上传、预览、确认、逐项结果的 DTO、错误码、幂等字段和上限。
- [ ] T1.4 定义 REST 分页/统一返回、设备快照、任务状态和 WebSocket 消息版本，补充到 `API_HANDOFF.md` 和 Swagger 设计稿。
- [ ] T1.5 为每个状态迁移列出发起者、前置条件、数据库变化、设备动作和失败恢复方式。
- [ ] T1.6 形成数据库改造决策：确认现有表复用范围、新增字段/表、数据迁移顺序、回滚方式和历史数据兼容策略。

## T2 [改造] 拆分监控与后台调度

**对应需求：REQ-06、REQ-07、REQ-12**

- [ ] T2.1 将全局 `farm.tasks.enabled` 拆为 `farm.monitor.enabled` 和 `farm.scheduler.enabled`，并保证缺省时二者均关闭。
- [ ] T2.2 为监控增加 `printer-ids` 白名单、轮询间隔、并发上限和逐设备失败隔离。
- [ ] T2.3 为调度器增加确认要求、单轮上限和明确的可处理任务状态集合。
- [ ] T2.4 修改 `PrinterMonitorTask`、`JobSchedulerTask` 的条件注解、配置绑定、启动日志和测试配置。
- [ ] T2.5 增加配置测试：只开监控、只开调度、二者都关、白名单为空、历史开关兼容。

## T3 [改造] 修复监控数据新鲜度

**对应需求：REQ-07、REQ-09、REQ-10**

- [ ] T3.1 梳理 `PrinterCacheServiceImpl` 全量打印机缓存的读写点，确保任务绑定、设备状态变化会失效或刷新缓存。
- [ ] T3.2 监控处理每台设备前读取数据库最新绑定和版本，不使用过期对象决定任务归属。
- [ ] T3.3 将设备不可达、查询超时、协议解析失败映射为统一健康状态和可重试错误，不让单设备异常终止轮询线程。
- [ ] T3.4 为“设备正在打印但 Farm 无绑定”“Farm 有绑定但设备 idle”“缓存旧绑定”补充单元测试。
- [ ] T3.5 在 `.77` 上用只读状态验证监控白名单和状态同步；不得对 `.62` 做真实控制测试。

## T4 [改造] 任务/打印机占用一致性

**对应需求：REQ-05、REQ-09**

- [ ] T4.1 检查 `farm_printer` 和 `farm_print_job` 的绑定字段、索引和版本字段，设计可回滚增量 SQL。
- [ ] T4.2 用数据库条件更新/乐观锁保证同一打印机不能被两个确认请求占用。
- [ ] T4.3 为创建、派发、取消、重试和进程重启设计幂等键及重复请求结果。
- [ ] T4.4 清理或恢复历史幽灵绑定，禁止用无审计的直接删除掩盖状态问题。
- [ ] T4.5 增加并发确认、重复确认、设备离线和服务重启恢复测试。
- [ ] T4.6 若现有 `current_job_id`/`printer_id` 双写无法稳定保证唯一占用，新增活动绑定表作为事实来源，并通过增量迁移逐步切换。

## T5 [改造] 完善 RRF/Klipper 生命周期

**对应需求：REQ-08、REQ-09、REQ-12**

- [ ] T5.1 扩展 `DeviceSnapshot` 和 `RrfApiClient`，解析 RRF `timesLeft`、取消/中止标志、文件位置和原始状态。
- [ ] T5.2 明确 `idle` 的完成、取消、急停、未开始和未知场景，不能仅凭 `idle` 将任务标记为 `COMPLETED`。
- [ ] T5.3 为上传、启动、暂停、恢复、取消、急停定义“请求发送/设备确认/Farm 最终状态”三个阶段。
- [ ] T5.4 适配 Moonraker 和 RRF 的错误、超时、断线、重连和命令幂等行为。
- [ ] T5.5 增加协议适配器单元测试；真实上传、启动、暂停、急停仅在用户指定的 `.77` 实机上执行并记录观察结果。

## T6 [改造] 统一控制和安全流程

**对应需求：REQ-02、REQ-08、REQ-11**

- [ ] T6.1 检查 `PrinterControlServiceImpl` 和 `PrintJobServiceImpl`，统一暂停、恢复、取消、急停的权限、状态校验和数据库回写。
- [ ] T6.2 保证 `UPLOAD_ONLY` 不启动设备，`START_PRINT` 必须有安全确认；批量动作默认不直接启动。
- [ ] T6.3 设计急停后的 `EMERGENCY_STOPPED/RECONCILING` 或等价状态，并要求重新查询/人工恢复。
- [ ] T6.4 统一异常错误码、设备原始错误摘要、任务重试标识和操作日志。
- [ ] T6.5 增加 ADMIN/OPERATOR 的 401/403、非法状态和重复控制请求测试。

## T7 [测试/文档] 回归现有单任务手动主流程

**对应需求：REQ-02、REQ-08、REQ-11、REQ-12**

- [ ] T7.1 在测试环境覆盖上传、创建任务、手动派发、安全确认、仅上传、启动和结果查询。
- [ ] T7.2 覆盖暂停/恢复、取消、急停和设备不可达的接口响应及数据库状态。
- [ ] T7.3 在 `.77` 真实设备上执行安全、短时、无运动/无加热/无挤出的测试文件；任何控制动作前先确认设备为空闲。
- [ ] T7.4 验收完成后记录任务 ID、设备原始状态、Farm 状态和人工观察结果，更新 `API_HANDOFF.md`。

## T8 [新增] 批量文件上传

**对应需求：REQ-03、REQ-11、REQ-12**

- [ ] T8.1 实现 `POST /api/v1/print-files/batch-upload`，沿用单文件上传的类型、大小、解析、归属和 RustFS 规则。
- [ ] T8.2 增加单项结果模型、批量数量/总大小上限和可重试错误。
- [ ] T8.3 处理重复文件、部分失败、临时文件清理和对象存储失败，不回滚已经成功且可追踪的其他项。
- [ ] T8.4 增加 Controller、Service、RustFS 异常和权限测试。
- [ ] T8.5 更新 Swagger、`API_HANDOFF.md` 和前端调用示例。

## T9 [新增] 批量分配预览

**对应需求：REQ-04、REQ-05、REQ-11**

- [ ] T9.1 实现 `POST /api/v1/print-jobs/batch/preview` DTO、策略校验和计划响应。
- [ ] T9.2 实现 `ONE_TO_ONE` 和 `ROUND_ROBIN`，明确数量不足、设备占用、离线和协议不兼容原因。
- [ ] T9.3 实现 `AUTO_MATCH` 的可解释匹配规则；第一版只使用当前已有设备能力字段，不凭空推断能力。
- [ ] T9.4 保证预览无副作用：不创建任务、不占用打印机、不上传设备、不启动打印。
- [ ] T9.5 保存计划版本、过期时间和确认令牌摘要，增加过期/篡改/资源变化测试。

## T10 [新增] 批量确认和逐项执行

**对应需求：REQ-05、REQ-09、REQ-11**

- [ ] T10.1 实现 `POST /api/v1/print-jobs/batch/confirm`，重新校验计划、权限、资源版本和设备状态。
- [ ] T10.2 原子锁定可执行项，创建任务并按 `UPLOAD_ONLY`/`QUEUE` 执行；默认禁止直接启动。
- [ ] T10.3 返回每项的 `jobId`、`printerId`、动作、状态、错误码、消息、尝试次数和重试标识。
- [ ] T10.4 实现重复确认幂等、部分成功、失败重试和计划终态。
- [ ] T10.5 前端可通过单项安全确认和启动接口继续完成需要打印的项目。
- [ ] T10.6 增加批量并发、部分失败、重复提交、过期计划和 WebSocket/REST 状态一致性测试。

## T11 [改造 + 新增] 可选后台自动派单

**对应需求：REQ-01、REQ-06、REQ-09、REQ-11**

- [ ] T11.1 实现 `GET/PUT /api/v1/dispatch/settings`，仅 ADMIN 可修改，全局默认关闭。
- [ ] T11.2 将后台调度改为生成 `BACKGROUND_AUTO` 计划，禁止直接复用旧的“按列表顺序绑定”逻辑。
- [ ] T11.3 实现计划预览、确认、过期和执行审计；未确认计划不上传、不启动。
- [ ] T11.4 统一待调度状态和资源条件，处理设备离线、任务不兼容、并发冲突和重启恢复。
- [ ] T11.5 增加调度器独立开关、分布式锁、重复轮询、计划幂等和默认关闭测试。

## T12 [改造] WebSocket 状态同步

**对应需求：REQ-08、REQ-10**

- [ ] T12.1 固化 `/ws/farm-status` 消息版本、事件类型、ID、时间和数据快照字段。
- [ ] T12.2 广播统一的 RRF/Klipper 状态和任务状态来源，不把协议原始值直接当作 Farm 最终状态。
- [ ] T12.3 增加事件序列/版本、断线重连和 REST 快照兜底说明。
- [ ] T12.4 评估局域网场景下的握手鉴权、连接上限和是否需要按用户/设备订阅。
- [ ] T12.5 增加 WebSocket 序列化、权限和异常不影响 REST 的测试。

## T13 [前端新增/改造] 前端联调

**对应需求：REQ-02、REQ-03、REQ-04、REQ-05、REQ-10**

- [ ] T13.1 在 `/home/codex/workspace/farm-ui` 接入新的契约类型、错误码和状态枚举。
- [ ] T13.2 完成单任务手动上传/选择设备/安全确认/启动页面。
- [ ] T13.3 完成批量文件上传、匹配预览、冲突提示、确认和逐项结果页面。
- [ ] T13.4 明确区分“用户发起的自动分配”和“后台自动派单”，后台开关默认展示为关闭。
- [ ] T13.5 接入 WebSocket 与 REST 快照恢复，处理上传取消、重试、空状态、离线状态和权限错误。

## T14 集成验收

**对应需求：REQ-07、REQ-08、REQ-09、REQ-12**

- [ ] T14.1 运行 `mvn test`、批量接口测试、权限测试和协议适配器测试。
- [ ] T14.2 使用当前 Docker MySQL/Redis/RustFS 做后端联调；不得使用 `docker compose down -v`。
- [ ] T14.3 在 `.77` 完成只读状态、单任务安全上传/启动/暂停/取消/急停验收；无真实打印动作时使用安全 G-code。
- [ ] T14.4 验证 `.62` 不在真实控制白名单中，后台调度默认不启动。
- [ ] T14.5 保存可复现的日志、请求摘要、响应和人工观察结论，更新验收文档。

## T15 发布和文档收口

**对应需求：REQ-11、REQ-12**

- [ ] T15.1 同步 `TODO.md`、`requirements.md`、`design.md`、`tasks.md`、`API_HANDOFF.md` 和 Swagger。
- [ ] T15.2 检查生产密钥、CORS、Swagger 暴露、上传目录、文件大小和日志脱敏。
- [ ] T15.3 记录数据库增量迁移、回滚方案、配置开关和真实设备限制。
- [ ] T15.4 完成阶段验收后创建普通提交；按用户约定在阶段结束时统一使用 GPG 签名提交。

## 执行规则

每次只推进当前未完成 Task：先阅读实际代码，完成实现和针对性测试，再更新本文件中的状态、根目录 `TODO.md` 和接口交接文档。真实 RRF 控制、上传、启动、暂停、急停仅允许在 `192.168.0.77` 上验证，并且每次操作前确认设备状态和测试文件安全性。
