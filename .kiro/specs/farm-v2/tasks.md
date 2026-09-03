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
- [x] T0.4 确认 v2 正式范围：单任务手动操作 + 用户确认的批量操作；后台自动派单移至 v3。

## T1 [改造 + 新增] 冻结 v2 业务和数据契约

**对应需求：REQ-01、REQ-02、REQ-03、REQ-04、REQ-05、REQ-10**

- [x] T1.1 将 `MANUAL`、`USER_BATCH`、`UPLOAD_ONLY`、`QUEUE`、`START_AFTER_CONFIRM` 写入统一领域枚举或契约常量；`AUTO_MATCH` 仅作为用户批量策略。
- [x] T1.2 已冻结 `QUEUED`、`ASSIGNED`、`UPLOADING`、`PRINTING`、`PAUSED`、`RECONCILING`、`COMPLETED`、`CANCELLED`、`FAILED` 状态和基础迁移规则；创建任务与调度扫描均使用 `QUEUED`。
- [x] T1.3 已定义批量上传结果、预览/确认请求与逐项计划结果、计划版本/确认令牌摘要、批量上限、单任务创建幂等键和文件/打印机资源摘要。
- [x] T1.4 已冻结 REST 分页/统一返回、设备快照、任务状态和 WebSocket `version/eventId/sequence`，并同步到 `API_HANDOFF.md`。
- [x] T1.5 已在 `design.md` 和 `API_HANDOFF.md` 列出主要状态迁移的发起者、前置条件、数据库变化、设备动作和失败恢复方式。
- [x] T1.6 已决定复用 `farm_printer.current_job_id`/`farm_print_job.printer_id`，新增批量计划表和创建幂等字段；迁移按 07-09 顺序执行，代码回滚不删表，已有数据卷须备份后手工升级。

## T2 [改造] 拆分监控与后台调度

**对应需求：REQ-06、REQ-07、REQ-12**

- [x] T2.1 将全局 `farm.tasks.enabled` 拆为 `farm.monitor.enabled` 和 `farm.scheduler.enabled`，并保证缺省时二者均关闭。
- [x] T2.2 为监控增加 `printer-ids` 白名单、轮询间隔、并发上限和逐设备失败隔离。
- [x] T2.3 保证调度器在 v2 默认关闭、不能被旧配置误启动，并为 v3 保留独立配置边界；不在 v2 实现自动派单业务。
- [x] T2.4 修改 `PrinterMonitorTask`、`JobSchedulerTask` 的条件注解、配置绑定、启动日志和测试配置。
- [x] T2.5 增加配置测试：二者都关、白名单为空、旧 `farm.tasks.enabled=true` 仍不会误启动；独立启用组合留待真实 profile 验收。

## T3 [改造] 修复监控数据新鲜度

**对应需求：REQ-07、REQ-09、REQ-10**

- [x] T3.1 梳理 `PrinterCacheServiceImpl` 全量打印机缓存的读写点，确保任务绑定、设备状态变化会失效或刷新缓存。
- [x] T3.2 监控处理每台设备前读取数据库最新绑定和版本，不使用过期对象决定任务归属。
- [x] T3.3 将设备不可达、查询超时、协议解析失败映射为统一健康状态和可重试错误，不让单设备异常终止轮询线程。
- [x] T3.4 为“设备正在打印但 Farm 无绑定”“Farm 有绑定但设备 idle”“缓存旧绑定”补充单元测试。
- [ ] T3.5 在 `.77` 上用只读状态验证监控白名单和状态同步；不得对 `.62` 做真实控制测试。

## T4 [改造] 任务/打印机占用一致性

**对应需求：REQ-05、REQ-09**

- [x] T4.1 已核对 `current_job_id`/`printer_id`、状态/用户/时间索引；增量迁移 08/09 只新增原子绑定说明和可空幂等字段/唯一索引，备份后可回滚代码。
- [x] T4.2 派发入口使用 Redis 互斥锁，并通过数据库 `current_job_id IS NULL AND status='IDLE'` 条件更新原子建立绑定；影响行数为 0 时回滚任务写入并返回占用冲突。
- [x] T4.3 单任务创建、取消、重试、重新排队和批量计划确认均具备幂等边界；跨进程恢复仍需真实运行验收。
- [ ] T4.4 清理或恢复历史幽灵绑定，禁止用无审计的直接删除掩盖状态问题。
- [~] T4.5 已增加批量计划并发认领和重复确认测试；设备离线及服务重启恢复仍需集成环境验证。
- [ ] T4.6 若现有 `current_job_id`/`printer_id` 双写无法稳定保证唯一占用，新增活动绑定表作为事实来源，并通过增量迁移逐步切换。

## T5 [改造] 完善 RRF/Klipper 生命周期

**对应需求：REQ-08、REQ-09、REQ-12**

- [x] T5.1 扩展统一设备快照和 `RrfApiClient`，解析 RRF `timesLeft`、取消/中止标志、文件位置、文件大小和原始状态。
- [x] T5.2 已明确 `idle` 的完成、取消、急停、未开始和未知场景：有证据才结束，无证据进入 `RECONCILING`，急停/`halted` 不自动完成。
- [x] T5.3 已明确协议动作分三阶段：适配器发送请求；返回成功仅表示设备接受请求；监控/重新查询确认后才写入 Farm 最终状态。
- [ ] T5.4 适配 Moonraker 和 RRF 的错误、超时、断线、重连和命令幂等行为。
- [ ] T5.5 增加协议适配器单元测试；真实上传、启动、暂停、急停仅在用户指定的 `.77` 实机上执行并记录观察结果。

## T6 [改造] 统一控制和安全流程

**对应需求：REQ-02、REQ-08、REQ-11**

- [x] T6.1 已统一暂停、恢复、取消、急停的适配器入口、任务状态校验、数据库回写和打印机互斥锁。
- [x] T6.2 `UPLOAD_ONLY` 只上传不启动，`START_PRINT` 强制安全确认；批量动作不直接绕过安全流程。
- [x] T6.3 急停后打印机写为 `ERROR`，绑定任务进入 `RECONCILING`，要求监控重新查询和人工处理。
- [~] T6.4 设备异常已统一进入协议异常/业务错误边界并记录可定位日志；任务级 retryable 错误和全部控制响应码仍待补齐。
- [~] T6.5 已补充暂停/急停状态回写测试；重复控制请求和设备最终状态确认仍待补齐。

## T7 [测试/文档] 回归现有单任务手动主流程

**对应需求：REQ-02、REQ-08、REQ-11、REQ-12**

- [ ] T7.1 在测试环境覆盖上传、创建任务、手动派发、安全确认、仅上传、启动和结果查询。
- [ ] T7.2 覆盖暂停/恢复、取消、急停和设备不可达的接口响应及数据库状态。
- [ ] T7.3 在 `.77` 真实设备上执行安全、短时、无运动/无加热/无挤出的测试文件；任何控制动作前先确认设备为空闲。
- [ ] T7.4 验收完成后记录任务 ID、设备原始状态、Farm 状态和人工观察结果，更新 `API_HANDOFF.md`。

## T8 [新增] 批量文件上传

**对应需求：REQ-03、REQ-11、REQ-12**

- [x] T8.1 实现 `POST /api/v1/print-files/batch-upload`，沿用单文件上传的类型、大小、解析、归属和 RustFS 规则。
- [x] T8.2 增加单项结果模型、批量数量/总大小上限和可重试错误。
- [x] T8.3 处理请求内同名同大小重复文件、部分失败、临时文件清理和对象存储失败，不回滚已经成功且可追踪的其他项。
- [x] T8.4 增加 Service 逐项结果、数量上限、单项校验和对象存储异常测试；Controller/权限沿用现有文件上传路由保护。
- [x] T8.5 更新 Swagger 注解、`API_HANDOFF.md` 和前端调用字段说明。

## T9 [新增] 批量分配预览

**对应需求：REQ-04、REQ-05、REQ-11**

- [x] T9.1 实现 `POST /api/v1/print-jobs/batch/preview` DTO、策略校验和计划响应。
- [x] T9.2 实现 `ONE_TO_ONE` 和 `ROUND_ROBIN`，明确数量不足、设备占用、离线和能力不兼容原因。
- [x] T9.3 实现 `AUTO_MATCH` 的可解释匹配规则；第一版只使用当前已有耗材、喷嘴和设备状态字段。
- [x] T9.4 保证预览无副作用：不创建任务、不占用打印机、不上传设备、不启动打印。
- [x] T9.5 已保存计划版本、过期时间、确认令牌摘要和资源版本摘要，并覆盖过期、资源变化和令牌篡改测试。

## T10 [新增] 批量确认和逐项执行

**对应需求：REQ-05、REQ-09、REQ-11**

- [x] T10.1 实现 `POST /api/v1/print-jobs/batch/confirm`，重新校验计划、权限和设备状态。
- [x] T10.2 通过打印机 Redis 锁逐项锁定并创建任务；`QUEUE`/`START_AFTER_CONFIRM` 默认不调用设备启动。
- [x] T10.3 返回每项的 `jobId`、`printerId`、状态、错误码、消息、尝试次数和重试标识。
- [x] T10.4 实现重复确认幂等、部分成功和计划终态；失败项标记为 `FAILED/RETRYABLE`。
- [x] T10.5 前端可通过现有单项安全确认和启动接口继续完成需要打印的项目。
- [~] T10.6 已覆盖预览无副作用、资源变化部分失败、过期计划和重复/并发确认基础测试；WebSocket/REST 一致性和真实跨进程恢复仍待补齐。

## T11 [-] 延期至 v3：后台自动派单

**对应需求：REQ-01、REQ-06、REQ-09、REQ-11**

- [-] T11.1 v2 不实现 `GET/PUT /api/v1/dispatch/settings` 及后台派单计划接口。
- [-] T11.2 v2 不生成 `BACKGROUND_AUTO` 计划，不执行后台扫描、匹配、派发或启动。
- [-] T11.3 v2 不在前端展示后台派单入口；相关需求另立 v3 规格。
- [-] T11.4 v3 再设计授权、确认、审计、失败重试、并发和恢复机制。

## T12 [改造] WebSocket 状态同步

**对应需求：REQ-08、REQ-10**

- [x] T12.1 固化 `/ws/farm-status` 消息版本、事件类型、`eventId`、`sequence`、时间和数据快照字段。
- [x] T12.2 广播统一的 `unifiedState`/任务状态，同时保留 `stateSource` 和 `rawState` 作为观测信息，不把原始协议值当作 Farm 最终状态。
- [x] T12.3 已增加 `version/eventId/sequence`，并约定断线、版本不连续时通过 REST 快照恢复。
- [x] T12.4 已确认局域网采用 JWT 握手、连接上限和农场级广播；按设备订阅暂不增加。
- [x] T12.5 已覆盖 WebSocket 序列化、JWT/禁用用户拒绝、连接清理、Ping 和业务异常隔离。

## T13 [前端新增/改造] 前端联调

**对应需求：REQ-02、REQ-03、REQ-04、REQ-05、REQ-10**

- [~] T13.1 前端已接入批量接口、`UPLOADING/RECONCILING` 状态和新的 WebSocket 元数据；完整契约类型化仍待补齐。
- [x] T13.2 单任务文件库/任务队列已串联选择设备、现场安全确认和启动打印；真实设备动作仍属于 T7/T14 现场验收。
- [x] T13.3 已完成批量上传、文件/设备选择、匹配预览、冲突提示、确认和逐项结果页面。
- [x] T13.4 页面只提供用户主动触发的匹配，不展示后台自动派单入口或开关。
- [~] T13.5 已接入 `sequence` 断档检测、REST 打印机快照恢复、离线状态、批量上传取消和可重试失败项；空/维护态和浏览器真实端到端仍待补齐。

## T14 集成验收

**对应需求：REQ-07、REQ-08、REQ-09、REQ-12**

- [ ] T14.1 运行 `mvn test`、批量接口测试、权限测试和协议适配器测试。
- [ ] T14.2 使用当前 Docker MySQL/Redis/RustFS 做后端联调；不得使用 `docker compose down -v`。
- [ ] T14.3 在 `.77` 完成只读状态、单任务安全上传/启动/暂停/取消/急停验收；无真实打印动作时使用安全 G-code。
- [ ] T14.4 验证 `.62` 不在真实控制白名单中，后台调度不会启动，且不会因旧配置缺省值改变任务绑定。
- [ ] T14.5 保存可复现的日志、请求摘要、响应和人工观察结论，更新验收文档。

## T15 发布和文档收口

**对应需求：REQ-11、REQ-12**

- [ ] T15.1 同步 `TODO.md`、`requirements.md`、`design.md`、`tasks.md`、`API_HANDOFF.md` 和 Swagger。
- [ ] T15.2 检查生产密钥、CORS、Swagger 暴露、上传目录、文件大小和日志脱敏。
- [ ] T15.3 记录数据库增量迁移、回滚方案、配置开关和真实设备限制。
- [ ] T15.4 完成阶段验收后创建普通提交；按用户约定在阶段结束时统一使用 GPG 签名提交。

## T16 [新增] v2 Local Edition：SQLite 与本地文件存储

**对应需求：REQ-03、REQ-05、REQ-09、REQ-13**

- [x] T16.1 已抽象 `FileStorage` 接口，实现本地文件存储，并保留 RustFS 实现；下载和打印任务读取只依赖统一端口。
- [x] T16.2 已增加 SQLite 驱动、Local profile、初始建表和 SQLite 分页方言；自定义 Mapper 的筛选、统计、搜索和 Upsert 分支已由 Local Edition 集成测试覆盖。
- [x] T16.3 已将打印机缓存、登录保护和单后端锁替换为本地进程实现；Local Edition 启动不要求 Redis。
- [~] T16.4 已创建数据目录、阻断本地路径穿越、检查可写和最低磁盘空间，并提供带恢复前保留措施的 SQLite+文件备份/恢复脚本及 Windows jpackage 构建脚本；正式恢复演练和 Windows 安装包验收仍待完成。
- [~] T16.5 已完成 Local profile 启动、health 探活和关键 Mapper/文件存储集成测试；REST/WebSocket 全契约对比仍待补齐。

## T17 [发布] v1 Server Edition：Docker Compose 正式部署

**对应需求：REQ-12、REQ-13**

- [x] T17.1 已维护 `Dockerfile` 和 `docker-compose.server.yml`，编排 Farm、MySQL、Redis、RustFS 四个服务。
- [x] T17.2 已提供 `.env.server.example`，生产密码、JWT、管理员密钥和 RustFS 凭据均通过环境变量注入。
- [x] T17.3 已完成健康检查、启动依赖、命名数据卷、内部网络和基础设施端口不对外暴露设计。
- [~] T17.4 Compose 配置和增量迁移顺序已验证；全新卷初始化、已有卷备份恢复和容器重启仍需在发布环境执行。
- [~] T17.5 已完成 Dockerfile/Compose 静态校验、`mvn package -DskipTests` 和本地 Server Edition 镜像构建；正式配置安全检查、发布环境启动/重启和发布验收仍需收口。

## 执行规则

每次只推进当前未完成 Task：先阅读实际代码，完成实现和针对性测试，再更新本文件中的状态、根目录 `TODO.md` 和接口交接文档。真实 RRF 控制、上传、启动、暂停、急停仅允许在 `192.168.0.77` 上验证，并且每次操作前确认设备状态和测试文件安全性。
