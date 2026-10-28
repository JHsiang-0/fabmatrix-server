# Farm v2 Kiro 执行任务

> 版本归属：本清单是 v2 功能实现基线，不代表产品发布 v2。当前对外产品为 `1.0.0` 准正式版；手动优先和 Local Edition 能力按 R100 发布门禁决定是否随版本交付，后台自动派单继续归入 v3。

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
- [x] T2.6 `1.0.0` 维护增强：已登记受监控打印机 DHCP 地址恢复，独立规格 `.kiro/specs/printer-ip-rediscovery/`。Local 启用 discovery 与 monitor 后自动首 10 秒/每 5 分钟及连续失败限频发现；ADMIN 可手动同步，保持手动任务流程和现有设备 ID/配置/绑定，无迁移。前端配套 R100-15 已验证；后端全量 714/714（新 IP 功能回归 163 项）、Windows 安装器契约通过；厂内设备和实际安装/升级验收未执行，新包构建单独记录。

## T3 [改造] 修复监控数据新鲜度

**对应需求：REQ-07、REQ-09、REQ-10**

- [x] T3.1 梳理 `PrinterCacheServiceImpl` 全量打印机缓存的读写点，确保任务绑定、设备状态变化会失效或刷新缓存。
- [x] T3.2 监控处理每台设备前读取数据库最新绑定和版本，不使用过期对象决定任务归属。
- [x] T3.3 将设备不可达、查询超时、协议解析失败映射为统一健康状态和可重试错误，不让单设备异常终止轮询线程。
- [x] T3.4 为“设备正在打印但 Farm 无绑定”“Farm 有绑定但设备 idle”“缓存旧绑定”补充单元测试。
- [x] T3.5 已在 `.77`（Farm ID `564`）上用临时 8081 只读监控实例验证白名单和状态同步：仅启用 `farm.monitor.printer-ids=564`，明确关闭 scheduler；RRF `state.status=idle` 被轮询并写入 Farm `IDLE`，状态历史持续产生记录。未对 `.62` 做真实控制测试。

## T4 [改造] 任务/打印机占用一致性

2026-10-08 监控配置补充回归（归属 `printer-monitoring-reliability` T2.5）：单/批开关写入仅修改开关和时间，避免覆盖并发任务绑定；逐项检查写入结果并整体回滚，提交后刷新缓存。新增 53 项专项测试，完整 `mvnw.cmd test` 528 项通过；不代替跨进程或真实设备验收。

**对应需求：REQ-05、REQ-09**

- [x] T4.1 已核对 `current_job_id`/`printer_id`、状态/用户/时间索引；增量迁移 08/09 只新增原子绑定说明和可空幂等字段/唯一索引，备份后可回滚代码。
- [x] T4.2 派发入口使用 Redis 互斥锁，并通过数据库 `current_job_id IS NULL AND status='IDLE'` 条件更新原子建立绑定；影响行数为 0 时回滚任务写入并返回占用冲突。
- [x] T4.3 单任务创建、取消、重试、重新排队和批量计划确认均具备幂等边界；批量计划确认仅限计划创建者或 ADMIN，PUBLIC 文件不转移计划所有权；跨进程恢复仍需真实运行验收。
- [x] T4.4 已先备份当前开发库，并执行 `scripts/repair-ghost-bindings.sql`：任务 1 指向已不存在的打印机 289 的历史幽灵绑定已解除，活动状态转为 `RECONCILING`，修复记录写入 `farm_binding_repair_audit`；复查后残留幽灵绑定为 0。脚本不删除任务、不自动重新派单，也不调用设备。
- [~] T4.5 已增加批量计划并发认领和重复确认测试；本次 `.77` RRF 重启后监控能从 `starting` 收敛回 `idle`，但设备离线及跨进程服务重启恢复仍需集成环境验证。
- [ ] T4.6 若现有 `current_job_id`/`printer_id` 双写无法稳定保证唯一占用，新增活动绑定表作为事实来源，并通过增量迁移逐步切换。

## T5 [改造] 完善 RRF/Klipper 生命周期

**对应需求：REQ-08、REQ-09、REQ-12**

- [x] T5.1 扩展统一设备快照和 `RrfApiClient`，解析 RRF `timesLeft`、取消/中止标志、文件位置、文件大小和原始状态。
- [x] T5.2 已明确 `idle` 的完成、取消、急停、未开始和未知场景：有证据才结束，无证据进入 `RECONCILING`，急停/`halted` 不自动完成。
- [x] T5.3 已明确协议动作分三阶段：适配器发送请求；返回成功仅表示设备接受请求；监控/重新查询确认后才写入 Farm 最终状态。
- [~] T5.4 已补充 RRF/Klipper 状态读取的一次短暂断线/超时重连重试，并统一保留 `OFFLINE/TIMEOUT/PROTOCOL_ERROR` 分类；暂停、恢复、取消、急停等控制命令不自动重试，避免超时后重复执行，控制幂等仍由业务锁和状态校验保证。完整设备断线恢复及跨进程幂等仍需现场/集成环境验证。
- [~] T5.5 RRF/Klipper 协议适配器单元测试已通过，并已在 `.77` 使用无运动/无加热/无挤出的等待文件完成真实上传、启动、暂停、恢复、取消和急停链路；设备物理运动/加热/挤出、正常生产文件和完整打印结果仍需现场观察。另发现长 `G4` 阻塞时 RRF `M0` 不能立即打断，取消接口成功与设备停止之间存在收敛窗口。

## T6 [改造] 统一控制和安全流程

**对应需求：REQ-02、REQ-08、REQ-11**

- [x] T6.1 已统一暂停、恢复、取消、急停的适配器入口、任务状态校验、数据库回写和打印机互斥锁。
- [x] T6.2 `UPLOAD_ONLY` 只上传不启动，`START_PRINT` 强制安全确认；批量动作不直接绕过安全流程。
- [x] T6.3 急停后打印机写为 `ERROR`，绑定任务进入 `RECONCILING`，要求监控重新查询和人工处理。
- [~] T6.4 设备异常已统一进入协议异常/业务错误边界并记录可定位日志；任务级 retryable 错误和全部控制响应码仍待补齐。
- [~] T6.5 已在 `.77` 验证暂停/恢复状态回写、取消解绑和急停后的设备重启收敛；重复控制请求、取消的设备最终停止确认和全部异常响应码仍待补齐。

### T6.6 打印历史导出维护增强

- [x] `1.0.0` / 前端 R100-16：独立 `POST /print-jobs/history/export`、公共 Excel/PDF 生成模块、终态筛选与 ADMIN/OPERATOR 资源边界；SQLite 列表与导出保持精确时间筛选，无数据库迁移和设备动作。后端764/764、前端430/430、浏览器3/3、Electron下载1/1及发布门禁通过；真实安装/数据验收待执行，配套包另记。需求、设计、实现与验证统一记录在 `.kiro/specs/print-history-export/`。
- [x] 导出规格 T4：独立可编辑筛选、PDF 横向紧凑记录表格、Excel 默认明细及原生 Table。后端专项39/39、完整768/768通过；真实业务30条PDF样例2页且已视检，Excel完整字段/流式表格回读通过。前端专项19/19、全量433/433、浏览器4/4、Electron下载1/1及完整发布门禁通过，两端新安装包构建/校验与服务端隔离HTTP导出冒烟通过，源码 `3492e19/3658a54`，证据和哈希见独立规格；无迁移、安装或真实设备动作，真实厂内数据验收仍NOT RUN。

## T7 [测试/文档] 回归现有单任务手动主流程

**对应需求：REQ-02、REQ-08、REQ-11、REQ-12**

- [~] T7.1 已完成本地运行实例的认证、文件分页/预览、单文件上传与清理、批量上传与清理、任务队列和批量预览验证；服务层/协议 Mock 已覆盖创建任务、手动派发、安全确认、仅上传、启动和结果查询，真实设备动作仍按 T5.5/T14.3 在 `.77` 验收。
- [~] T7.2 已由服务层、协议适配器和 Controller 测试覆盖暂停/恢复、取消、急停及设备不可达错误分类和状态回写；本次 `.77` 已实际观察到 `processing -> paused -> processing`，取消后设备停止存在延迟，急停后恢复 `idle`。正常生产文件的物理副作用仍需现场观察。
- [~] T7.3 已在 `.77` 执行无运动/无加热/无挤出的安全探针和控制响应验证；真实打印过程中的物理动作仍需用户现场观察。
- [~] T7.4 已记录任务 ID、设备原始状态、Farm 状态和响应结果到 `API_HANDOFF.md`；本次已补录监控轮询产生的状态历史和重启恢复，浏览器端实时状态及人工物理观察仍待补齐。

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
- [x] T10.4 实现重复确认幂等、部分成功和计划终态；失败项标记为 `FAILED/RETRYABLE`；批量任务使用确定性幂等键，已有任务的异常结果标记 `RECOVERY_REQUIRED`。
- [x] T10.5 前端可通过现有单项安全确认和启动接口继续完成需要打印的项目。
- [~] T10.6 已覆盖预览无副作用、资源变化部分失败、过期计划和重复/并发确认基础测试；WebSocket/REST 一致性和真实跨进程恢复仍待补齐。
- [x] T104 批量失败项恢复：增加 `POST /api/v1/print-jobs/batch/retry-preview`，仅允许 `RETRYABLE && jobId IS NULL` 项重新预览；已有 `jobId` 的项固定为 `RECOVERY_REQUIRED/OPEN_EXISTING_JOB`，并保留来源计划/明细审计关联。

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
- [x] T12.5 已覆盖 WebSocket 序列化、JWT/禁用用户拒绝、连接清理、Ping 和业务异常隔离；2026-09-03 对运行中的 8080 实例完成 JWT 握手验证，收到 `version=1`、有效 `eventId/sequence` 的 `SNAPSHOT`，快照包含 49 台设备并正常以关闭码 1000 结束。

## T13 [前端新增/改造] 前端联调

**对应需求：REQ-02、REQ-03、REQ-04、REQ-05、REQ-10**

- [~] T13.1 前端已接入批量接口、`UPLOADING/RECONCILING` 状态和新的 WebSocket 元数据，并修正打印机协议、文件名搜索、目录上传和任务幂等键契约；完整契约类型化仍待补齐。
- [x] T13.2 单任务文件库/任务队列已串联选择设备、现场安全确认和启动打印；真实设备动作仍属于 T7/T14 现场验收。
- [x] T13.3 已完成批量上传、文件/设备选择、匹配预览、冲突提示、确认和逐项结果页面。
- [x] T13.4 页面只提供用户主动触发的匹配，不展示后台自动派单入口或开关。
- [~] T13.5 已接入 `sequence` 断档检测、REST 打印机快照恢复、离线状态、批量上传取消和可重试失败项；空/维护态和浏览器真实端到端仍待补齐。

## T14 集成验收

**对应需求：REQ-07、REQ-08、REQ-09、REQ-12**

- [x] T14.1 `mvn -q test`、批量计划/幂等测试、权限/输入校验测试和 RRF/Klipper 协议适配器测试均通过；真实设备动作不在此项内。
- [x] T14.2 已使用当前 Docker MySQL/Redis/RustFS 完成后端健康、登录、分页查询、临时文件上传/下载/删除和 WebSocket 快照冒烟；发现已有数据卷未执行 07-10 增量迁移后已备份并补齐，任务队列再次返回 200；未删除数据卷。
- [~] T14.3 已在 `.77` 完成只读状态、无动作探针、单任务安全创建/派发/确认/上传/启动/暂停/恢复/取消/急停和监控状态回读；物理动作、正常生产文件、取消最终停止语义和完整打印链路仍待观察。
- [x] T14.4 已核对 `.62` 不在控制白名单，`farm.monitor.enabled`/`farm.scheduler.enabled` 默认关闭，旧 `farm.tasks.enabled` 不会误启动新任务；配置测试通过。
- [~] T14.5 已保存请求摘要、响应、状态历史和代码/设备探测结论到 `API_HANDOFF.md`；待用户现场观察正常生产文件，并确认急停后的设备复位和最终签收结论。

## T15 发布和文档收口

**对应需求：REQ-11、REQ-12**

- [~] T15.1 已同步 `TODO.md`、`requirements.md`、`design.md`、`tasks.md`、`API_HANDOFF.md`、运维文档和主要 Swagger 注解；完整生成文档与逐接口自动比对仍待收口。
- [~] T15.2 已检查生产配置的密钥环境变量、显式 CORS、Swagger 关闭、上传目录、文件大小和日志脱敏实现，并有安全配置测试；真实发布环境密钥和来源仍需部署前复核。
- [x] T15.3 已在 `API_HANDOFF.md`、`OPERATIONS.md` 和本任务文档记录数据库增量迁移、备份/回滚边界、配置开关和 `.77` 真实设备限制。
- [ ] T15.4 完成阶段验收后创建普通提交；按用户约定在阶段结束时统一使用 GPG 签名提交。

## T16 [新增] v2 Local Edition：SQLite 与本地文件存储

**对应需求：REQ-03、REQ-05、REQ-09、REQ-13**

- [x] T16.1 已抽象 `FileStorage` 接口，实现本地文件存储，并保留 RustFS 实现；下载和打印任务读取只依赖统一端口。
- [x] T16.2 已增加 SQLite 驱动、Local profile、初始建表和 SQLite 分页方言；自定义 Mapper 的筛选、统计、搜索和 Upsert 分支已由 Local Edition 集成测试覆盖。
- [x] T16.3 已将打印机缓存、登录保护和单后端锁替换为本地进程实现；Local Edition 启动不要求 Redis。
- [~] T16.4 已创建数据目录、阻断本地路径穿越、检查可写和最低磁盘空间，并提供带恢复前保留措施的 SQLite+文件备份/恢复脚本及 Windows jpackage 构建脚本；正式恢复演练和 Windows 安装包验收仍待完成。
- [~] T16.5 已完成 Local profile 启动、health 探活和关键 Mapper/文件存储集成测试；覆盖 PUBLIC 根文件的重复迁移保持、非文件夹父级启动阻断及不同用户同名 PRIVATE 默认根目录；REST/WebSocket 全契约对比仍待补齐。
- [~] T16.6 已完成 FabMatrix Server Windows Local Edition 正式安装包脚本、统一数据布局、真实旧 Farm 配置/数据非破坏性迁移、升级状态保留与安装失败传播、服务/防火墙/显式私有 ACL、独立纯 JDK 托盘控制器、带 ACL 回滚的可移植备份恢复和临时目录契约回归；已构建安装包并校验产物。干净 Windows 机器上的 C 盘/非 C 盘安装、升级、卸载、graceful shutdown 日志和数据保留验收仍待完成。
- [x] T16.7 为 Windows Local Edition 增加可替换的安装器、主程序、托盘启动器和运行时托盘图标资源，提供仅清理已知构建产物的安全 CleanOutput 流程，补充契约测试和使用说明；已使用 `server_logo.png` 生成并替换全套 Windows 图标，在本机使用 Inno Setup 6 完成 Full 构建，392 tests passed，发布候选的体积和 SHA-256 以输出目录中的 `release-info.txt`、`checksums.txt` 为准。
- [x] T16.8（P1.7.8）修复 Windows Local Edition 托盘菜单中文显示为方块：使用 Swing `JPopupMenu`/`JMenuItem` 自绘菜单规避 Windows AWT native peer 忽略 Java 字体，按 `canDisplayUpTo` 选择中文字体并保留完整英文回退；右键菜单通过可释放的 `JWindow` anchor 显示并约束在当前显示器可用边界内，外部点击或焦点离开时关闭且不受 popup 自身焦点切换影响；jpackage 使用 `ALL-MODULE-PATH` 保留完整 JDK runtime，并覆盖源码、打包 launcher、Swing 菜单模型及 runtime 模块自检。已完成 Full 安装包构建、契约检查及 392 项 Maven 测试。
- [x] T16.9（P1.7.9）将新部署的默认 HTTP/WebSocket 端口统一为 `28081`：覆盖 dev/local/prod、Server Edition Docker、Windows Local Edition 安装器/防火墙/托盘及前端首次连接默认值；显式环境变量和客户端已保存地址继续优先；升级安装保留已有 `application.yml` 端口，并让 Windows 服务、防火墙及普通权限托盘共用安装时解析的实际端口，通过非敏感只读元数据避免托盘读取受保护配置；已补充默认值、覆盖、ACL 与安装器契约回归。`mvnw.cmd test` 392 项、Windows 安装器契约、前端 342 项测试、lint、普通/Desktop 构建及干净环境 Electron dist 回归均通过。
- [x] T16.10（P1.7.10）修复 Windows Local Edition 覆盖安装失败：释放 `ServiceController` 句柄并等待旧服务从 SCM 完全消失，WinSW 注册使用 30 秒有界重试；服务安装按阶段写入数据目录下的持久日志，失败提示直接给出日志位置；服务注册失败时不再执行安装后托盘启动项。安装契约和完整后端测试通过后重新构建发布候选。
- [x] T16.11（P1.7.11）兼容旧安装留下的异常数据 ACL：在读取/迁移现有配置前，仅对 `config/database/backups/files/logs` 五个已知数据目录恢复 Administrators/SYSTEM 所有权与完全控制，随后继续使用正式 ACL 策略重新收紧；避免管理员安装进程因无法读取 `application.yml` 而在配置阶段退出。
- [x] T16.12（P1.7.12）修正旧数据 ACL 恢复的误判：接管所有权后先递归重置五个固定数据子树的继承 ACL，清除优先于 allow 的历史显式 deny，再授予 Administrators/SYSTEM；恢复阶段不再使用 `icacls /C` 忽略子项错误，任一 ACL 修复失败即中止并保留日志。
- [x] T16.13（P1.7.13）解除 Windows 防火墙自动化对核心安装的阻断：服务注册成功后，私有网络防火墙规则创建失败改为持久警告而非卸载服务并回滚；安装诊断日志迁移到与业务 ACL 隔离的 `%ProgramData%\FabMatrix\installer`，确保权限或后置步骤失败时仍可读取准确阶段。
- [x] T16.14（P1.7.14）修复 Windows Local Edition 既有服务日志 ACL：对 `config/database/backups/files/logs` 五个固定数据目录树中的所有既有目录和文件写入明确的 SYSTEM/Administrators 直接权限，并为 `files/logs` 写入 Users 只读权限；目录继续保留未来文件继承策略，覆盖 `logs/service` 下已有 WinSW stdout/stderr 日志，补充非提升可清理的 ACL fixture 契约回归。
- [x] T16.15（P1.7.15）修复 Windows Local Edition 服务注册后 Java 子进程因完整 `logging.file.name` 被重复拼接而退出的问题：日志配置直接使用完整文件路径并保留默认/Server Edition 兼容回退，移除 WinSW `LOG_PATH` 注入；在固定数据 ACL 递归变更前拒绝根及后代 reparse point，`takeown` 使用 `/SKIPSL`，并补充日志启动探针、路径合同、junction 外部 sentinel 和继承语义回归。`mvnw test` 392 项、Windows installer contract、打包 Local health/log 探针和 Inno Setup Full 构建均通过；安装包 `dist/windows-installer/FabMatrixServer-Setup.exe` 为 119597612 bytes，SHA-256 为 `CEFA62849F475B4447C97FF496226C72C760F2BA71169ED282CAAF39324AD43A`。
- [x] T16.16（P1.7.16）修复 Windows Local Edition 托盘服务控制的位置参数绑定和 UAC 错误回传：服务控制脚本改为安全的位置参数调用，提升进程使用当前用户临时目录中的随机结果文件回传成功/真实错误，抑制 ProgressRecord/CLIXML 并在 `finally` 清理；升级安装将纯数字旧默认端口 `8080` 原子迁移为请求的默认 `28081`，保留合法自定义端口和显式 `SERVER_PORT` 优先级，并统一同步 WinSW、防火墙和托盘元数据。已补充参数绑定、CLIXML 清洗/临时文件清理、端口迁移和打包托盘自测合同；`mvnw.cmd test` 392 项、Windows installer contract 和 Full installer build 均通过。安装包 `dist/windows-installer/FabMatrixServer-Setup.exe` 为 119602125 bytes，SHA-256 为 `B6CA73258266F993107D2C307C57173A9CBF02985E71E69BA4F525D6E0FB01BD`。
- [x] T16.17（P1.7.17）修复 Windows Local Edition 旧 SQLite 升级启动顺序：`local-schema.sql` 不再在旧 `farm_print_job` 补齐 TC-007 演进列前创建 `retry/root` 唯一索引，索引统一由 `LocalSchemaMigration` 在补列和回填后幂等创建；审计初始 Local schema 到当前演进字段/索引并修正现有 legacy 假覆盖测试，新增真实 pre-TC007 bootstrap 顺序测试和临时 SQLite 文件的 Local profile 启动/HTTP health 集成探针，保留旧任务及文件/打印机关联。`mvnw.cmd test` 393 项、Windows installer contract 和 Full installer build 均通过。安装包 `dist/windows-installer/FabMatrixServer-Setup.exe` 为 119600999 bytes，SHA-256 为 `9CDD4A4B22140B79CFB3677D580A7EC36E1880369357C587B32A43AD13A9A680`。
- [x] T16.18 修复 Windows Local Edition 升级安装期间 `runtime\bin\jimage.dll` 被旧服务进程树占用：预检在复制文件前保存既有服务状态，临时阻断 WinSW `onfailure` 重启，退出旧托盘并按目标安装目录等待/清理服务包装器、服务器及 runtime 子进程；仅允许对已验证的 `{app}` 进程做有界 fallback 终止，失败时非零中止并尽力恢复升级前状态；Inno 传入 `{app}`，并补充不操作真实服务的安装器契约护栏。`mvnw.cmd test` 393 项、Windows installer contract 和完整 Windows 安装器构建均通过；安装包 `dist/windows-installer/FabMatrixServer-Setup.exe` 为 119616592 bytes，SHA-256 为 `B1CFFFBF7DCB3BE1DF7E8F1A20053986A16F78FFFB09A0974F66FB9334946193`。

### T16.19 [新增] Windows Local Edition 双模式卸载

- [x] T16.19 为 Windows Local Edition 增加默认保留数据卸载与显式完全卸载双模式：普通/静默卸载删除服务、托盘/程序和防火墙规则但保留业务数据；交互式完全卸载要求模式选择和默认聚焦“否”的第二次不可逆确认，命令行仅接受 `/PURGEDATA=CONFIRM`。主审修复后，卸载清理由 `CurUninstallStepChanged(usUninstall)` 单次同步执行并强校验退出码，静默状态使用卸载器专用 `UninstallSilent` API；独立 `purge-local-data.ps1` 检查完整驱动器根、目标和每个已存在祖先的 reparse point，并以逐节点复检、LiteralPath、非递归后序删除受控 DataRoot。契约包含隔离 ISCC 编译以及不同 cwd、DataRoot 根 junction、InstallDir 根/祖先 junction、后代 junction 自测；`mvnw.cmd test` 393 项、Windows installer contract、payload/启动探针和完整 installer build 已通过。

### T16.20 [修复] 完全卸载旧版残留与重装端口回灌

- [x] T16.20 修复完全卸载仅清理当前 DataRoot、遗留 `%ProgramData%\Farm` 导致重装重新迁回账号和 8080 配置的问题：普通/静默卸载仍保留当前数据、旧版数据和安装诊断；显式完全卸载在原二次确认后，按严格策略清理当前 DataRoot、经 `data\farm.db`/非空 `config`/非空 `uploads` 识别的旧版 Farm 根，以及与当前根去重的 `%ProgramData%\FabMatrix` 项目诊断残留。所有目标继续执行完整祖先/reparse 校验与 LiteralPath 非递归后序删除。旧版迁移脚本通过原子结构化结果明确本次是否真实迁移，安装脚本仅在服务升级或真实 legacy migration 时把旧纯数字默认 8080 原子迁为 requested default 28081，自定义端口和显式 `SERVER_PORT` 保持优先；临时夹具、PowerShell AST、隔离 ISCC、393 项测试和完整安装器构建覆盖同步更新。

### T16.21 [修复] 统一认证入口密码长度规则

- [x] 将首次管理员初始化、普通登录、管理员创建用户、个人修改密码和遗留注册 API 统一为 6-20 个字符，仅限制长度，不再要求大小写字母或数字；保留确认密码一致、旧密码校验、BCrypt、登录保护、最大长度和权限逻辑。
- [x] 同步 UserLoginDTO、UserRegisterDTO、ChangePasswordDTO 的 Bean Validation、OpenAPI 描述、API_HANDOFF/协作文案及前端 Mock 契约。
- [x] 增加 5/6/20/21 位、纯数字/纯字母和确认密码不一致边界测试，并完成 `mvnw.cmd test` 回归。

### T16.22 [修复] Windows Local Edition 默认启动设备监控

- [x] 修复安装版虽然单台设备 `monitor_enabled=true`，但全局 `farm.monitor.enabled=false` 导致 `PrinterMonitorTask` 未创建、设备状态长期停留在 `UNKNOWN` 的问题。
- [x] Local Profile 和全新安装配置默认启用状态监控；WinSW 服务显式注入 `FARM_MONITOR_ENABLED=true`，使覆盖安装也能纠正旧候选版保留的关闭配置。
- [x] 保留数据库中的单台设备监控开关，后台任务自动派单继续关闭；补充 Local 配置、生成配置和 WinSW XML 安装器契约回归。
- [x] Windows 安装器契约、打包 Local 启动/健康探针和后端 442 项测试通过；新安装包 SHA-256 为 `F9BD0BD0E5ED23C7578D90859153C7C7C6D2C9B9A8169BE9E8BDB5AE3F82B22A`。

### T16.23 [整理] 数据库初始化、升级与历史基线目录

- [x] MySQL/SQLite 初始化统一放入 `db/init/`，原 `local-schema.sql` 改名为 `sqlite-schema.sql` 并同步 Local profile 和所有测试引用。
- [x] 迁移及预检按数据库放入 `db/migration/mysql/`、`db/migration/sqlite/`；`farm.sql`、`V*.sql` 移入 `db/archive/`，40 个移动文件 SHA-256 保持不变。
- [x] 同步 API、运维、布局迁移和协作说明，新增 `db/README.md`；`LocalSchemaMigration` 执行逻辑不变，不操作真实数据库、备份表或持久化卷。
- [x] `mvnw.cmd clean test` 全量 768/768 通过，包含 SQLite 新库/旧库升级回归；隔离 MySQL 初始化和关键约束检查通过。未执行安装器、真实库升级或设备动作。

### T16.24 [维护] 开发 MySQL 与初始化基线对齐

- [x] 复核 `localhost:3306/farm` 并创建完整备份，在无网络/tmpfs MySQL 成功恢复、演练 18/21/22/23 号迁移和重复执行。
- [x] 新增 `23-align-setup-state-and-job-default.sql`，保留原业务数据，补齐 setup 状态并对已有用户永久关闭首次入口，任务新记录默认 `QUEUED`。
- [x] 按用户授权应用到真实开发库；原有业务、审计和备份记录逐行一致，11 张业务/系统表结构对齐。备份及环境范围见 `docs/DATABASE_INITIALIZATION.md`。
- [x] `mvnw.cmd test` 768/768、隔离 MySQL 初始化/约束/迁移幂等及 setup 不重开回归通过；不代表已执行生产升级、设备动作或安装验收。

### T16.25 [体验] Windows 中文安装向导与托盘连接说明

- [x] 固定简体中文语言资源，欢迎/数据位置/安装确认/完成页面说明服务端与客户端关系、实际端口、数据保留及首次管理员流程。
- [x] 卸载使用明确的保留/删除/取消按钮，异常返回按取消处理；保留默认“否”的二次删除确认和静默安全规则。
- [x] 新增只读 `installation-summary.ps1`，完成页依据实际端口元数据、服务状态和健康结果显示地址；托盘新增连接说明及地址复制，文件/日志菜单中文化。
- [x] 全量 Maven 768/768、Windows 安装器契约、Inno 实际编译、摘要端口/状态及共享 runtime/托盘 launcher/隔离 Local 启动检查通过。
- [x] 新包统一为 `dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe`：145406559 bytes，SHA-256 `80F71087934B053FFBC965D69BE030A3FB28365127DDD9DE3BC1AE6EF8B0D2BE`。没有实际安装、升级、卸载或设备动作；Windows 原生 UI 自动化管道不可用，页面排版和交互视检为 NOT RUN。

### T16.26 [整理] 单一 Windows 安装产物与临时备份清理

- [x] 按用户要求移除仓库根 `backup` 和旧的安装产物至 Windows 回收站，将已验证的中文安装产物统一迁入默认 `dist/windows-installer`；不再保留语言后缀的第二套输出目录。
- [x] 构建脚本继续使用原有默认输出路径，安装包大小及 SHA-256 不变；同步备份历史状态和产物路径说明。未操作真实数据库或已安装服务。

### T16.27 [优化] 升级预检托盘退出与安装阶段进度

- [x] 无主窗口旧托盘不再跳过关闭后进入 45 秒残留等待；仅在安装路径、PID 和创建时间重新核实后结束当前安装的托盘，有主窗口时保留短时正常退出机会。SCM 正常停止后台服务及原有退出检查/恢复边界不变。
- [x] 新增 `installation-progress.ps1`，向导通过 `ExecAndLogOutput` 显示中文阶段及已用时间；等待进程释放/健康检查时持续报告，预检与服务日志记录每阶段耗时，忽略任意进程输出作为界面文案。
- [x] 新增隔离无窗口进程回归：打包测试关闭托盘 611ms，另一安装的同名托盘及后台进程不受影响；验证心跳不会污染等待函数布尔值，验证阶段文件与日志。Maven 768/768、Windows 安装器契约、Inno 编译、共享 runtime/launcher 和隔离 Local 启动通过。
- [x] 当前包 `dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe`：145406420 bytes，SHA-256 `5F4F32F23410F426E10626F879AD61B05B57D99B45179C0E3E0AFDDF04D8B31E`；覆盖 T16.25 的历史包，保留唯一默认输出目录并清理 work。没有停止当前真实服务或执行真实覆盖安装/卸载、数据库变更、设备动作；实际升级总耗时和页面视检仍待验收。

### T16.28 [修复] 托盘菜单焦点与明确停服退出

- [x] 将无可见 Frame/Dialog owner 的不可聚焦 JWindow 菜单锚点改为无边框 JDialog；保留菜单内部焦点切换保护，失焦/外部点击/Esc 清理监听和窗口，菜单项先收起菜单再执行，延迟回调只作用于对应锚点。
- [x] 保留普通退出不停止服务，增加默认取消的“停止服务并退出”，提示全部客户端断开且打印机不会自动停止；复用 UAC/SCM 控制并复核 Stopped 后才退出，取消或失败保留托盘，串行化服务操作。
- [x] 原生窗口对象不显示的自测验证可聚焦性、合成失焦/Esc 清理，以及模拟停服取消/失败/成功顺序；Maven 768/768、Windows 契约、共享 runtime/launcher 和隔离 Local 启动通过。未停止当前真实服务，真实桌面点击/UAC/停服交互因 UI 管道不可用仍待人工验收。
- [x] 当前唯一包 `dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe`：145412578 bytes，SHA-256 `22312FC720FA295EAA393B7F0E1F03CA9463F8CECB81ECFACEFDC0B0BF814677`，覆盖历史构建产物并清理 work；未实际安装或卸载。

### T16.29 [调整] Windows 程序中文显示名称

- [x] 开始菜单、当前用户启动项、已安装应用、安装器属性及托盘标题/提示改为“嘉东三维服务器”；jpackage 为服务端和托盘 EXE 写入中文显示描述，构建时读取原生版本资源验证。
- [x] 覆盖安装仅清理旧 `FabMatrixServerTray.lnk` 开始菜单和启动项快捷方式，避免重复入口；保留内部服务 ID、可执行文件名、安装路径及安装器 AppId，以兼容原安装。
- [x] Maven 768/768、Windows 安装器契约及打包托盘自检通过。当前唯一包 `dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe`：145415489 bytes，SHA-256 `AB47D1762D5BEF59E89E6436E2752413F7DC595070A9C5348E5564D4F3783CDE`，覆盖 T16.28 历史产物；清理生成 work。未安装/卸载或停止当前真实服务，覆盖安装后的系统显示待人工验收。

### T16.30 [扩展] 单农场账号安全、岗位与登录会话

- [x] 管理员重置临时密码，新建员工及重置后必须改密；BCrypt 存储，递增 token_version 并撤销旧会话。强制改密仅允许身份/本人改密/退出接口，WebSocket 拒绝连接；首个管理员自设密码不重复强制。
- [x] 固定岗位增加 VIEWER；读取沿用公有文件/本人任务及设备共享规则，生产写接口拒绝。集中定义权限名并返回客户端；补齐文件 capability、路由、设备安全确认/启动/控制、任务和文件入口。
- [x] farm_login_session 持久化独立登录，支持个人/管理员单会话撤销、其他全部/全部撤销和主动注销。五分钟节流活动更新；REST、WebSocket 发送/心跳共用有效性检查，事务提交后关闭撤销连接。
- [x] 复用审计查询，增加密码、岗位前后变化和会话撤销事件；客户端增加强制改密页、密码重置弹窗、个人/管理员会话和审计动作名称。详见 docs/ACCOUNT_SECURITY.md，前端任务 AS-001。
- [x] 更新 MySQL/SQLite 初始化及 24 号迁移；SQLite 自动补齐，MySQL 手工一次性升级。真实隔离 MySQL 8.4 空库/旧结构升级和 SQLite 旧库启动通过，原密码和已有账号默认不强制改密。
- [x] Maven 780/780、前端 436/436、发布门禁与 Electron dist 通过。真实隔离 SQLite/JWT/API 及两个 WebSocket 验证改密、重置、越权拒绝、单会话撤销只关闭对应连接（1008）和审计无密码内容。
- [x] 当前服务端包 dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe：145433083 bytes，SHA-256 `F97BC397D4628211024179D5C8F253FAF0067DA617F9FD3B4F9084409A74337B`。保持唯一默认目录，未安装或停止真实服务；两端应同步升级，旧 Token 需重新登录。
- [x] 客户端全量浏览器 30 通过、4 个默认关闭的批量派发场景跳过。最终客户端包 `release/FabMatrix-Client-Setup-1.0.0.exe`：128297727 bytes，SHA-256 `72B0F79D3D9804A649034CF8FDC35E7321EAE54C4CD3D0BFE0FF521C641D322E`，ASAR 功能标记校验通过；前端本地提交 `6673688`，完整记录见 AS-001。

### T16.31 [修复] Windows 错误登录导致单连接池等待

- [x] 只读核对实际安装服务、端口、profile、日志和健康接口；发现登录连接等待 30001ms（total=1、active=1、idle=0、waiting=17），服务仍在运行，实际为 local/SQLite。
- [x] 新增真实 Local 单连接池回归；修复前错误密码 HTTP 500，REQUIRES_NEW 审计无法申请第二条连接。通过隔离调试确认 active=1/idle=0，修复后进入同事务审计、返回 401/355ms、active=0。
- [x] 引入 LoginRejectedException，只允许预期凭据拒绝提交同事务失败审计；其他数据库/迁移异常仍回滚。不增大 SQLite 连接池，不改数据库结构或 application-prod.yaml。
- [x] 全量 782/782、安装器契约和打包启动通过；隔离真 JWT/API/双 WebSocket 冒烟加入错误密码后健康/身份仍可用检查，原有账号安全回归保持通过。所有代理断点移除，用户断点保留。
- [x] 新包 `dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe`：145433849 bytes，SHA-256 `A4FFB5E2C5C42992EFBF1AED47D6C012042B3668EB6817D2CD911269EA38AB33`；清理生成 work。现场服务未替换、实际业务库与网络设置未改；需覆盖安装后复核。现场防火墙 Public 当前关闭，未把规则类型不匹配作为已确认原因。完整证据见 docs/WINDOWS_LOGIN_AVAILABILITY.md。

## T17 [发布] v1 Server Edition：Docker Compose 正式部署

**对应需求：REQ-12、REQ-13**

- [x] T17.1 已维护 `Dockerfile` 和 `docker-compose.server.yml`，编排 Farm、MySQL、Redis、RustFS 四个服务。
- [x] T17.2 已提供 `.env.server.example`，生产密码、JWT、管理员密钥和 RustFS 凭据均通过环境变量注入。
- [x] T17.3 已完成健康检查、启动依赖、命名数据卷、内部网络和基础设施端口不对外暴露设计。
- [~] T17.4 已使用 `.env.server.example` 通过 Server Edition 和开发版 Compose 配置校验，并核对增量迁移顺序；全新卷初始化、已有卷备份恢复和容器重启仍需在发布环境执行。
- [~] T17.5 已完成 Dockerfile/Compose 静态校验、`mvn package -DskipTests` 和本地 Server Edition 镜像构建；正式配置安全检查、发布环境启动/重启和发布验收仍需收口。

## 执行规则

每次只推进当前未完成 Task：先阅读实际代码，完成实现和针对性测试，再更新本文件中的状态、根目录 `TODO.md` 和接口交接文档。真实 RRF 控制、上传、启动、暂停、急停仅允许在 `192.168.0.77` 上验证，并且每次操作前确认设备状态和测试文件安全性。
