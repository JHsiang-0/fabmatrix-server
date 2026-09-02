# Farm v1 可执行任务清单

版本：v1.0

状态：持续执行中；后端 P0/P1 已完成，外部环境与前端任务待验收

目标：[PROJECT.md](../../../PROJECT.md)

需求：[requirements.md](./requirements.md)

设计：[design.md](./design.md)

## 使用规则

- 一次只实现一个最小 Task，完成后再进入下一个 Task。
- `[x]` 只有在代码、测试和验收均完成后才允许勾选。
- `[~]` 表示正在执行；实现中断时必须在备注中说明原因。
- 每个 Task 完成后同步 `TODO.md`、`API_HANDOFF.md`，执行 `git diff --check` 和 `mvn test`，再创建本地提交。
- 前端任务不填写当前后端仓库不存在的文件路径；接入时在真实前端仓库记录路径。
- RRF 真实能力必须有官方资料或真实设备证据，Mock 只能证明解析和调用边界。

## 0. 当前基线核对

- [x] T0.1 核对当前仓库、`TODO.md`、`API_HANDOFF.md` 和工作区状态。
  - 验收：确认 P0.1–P0.6、协议适配基础和 WebSocket 消息已按后续 Task 完成；剩余为真实环境、前端和端到端验收。
  - 证据：提交 `54dbe14`、当前源码和测试结果。
- [x] T0.2 生成项目级 Kiro 文档入口。
  - 产物：`PROJECT.md`、`requirements.md`、`design.md`、`tasks.md`。
  - 本次只完成文档，不代表后续代码 Task 已完成。

## 1. P0 协议适配基础

- [x] T1.1 新增协议领域基础模型。
  - 目标：`PrinterProtocolType`、统一设备状态、端点、操作和失败分类。
  - 验收：大小写/历史值规范化、未知协议拒绝、无敏感字段序列化。
  - 测试：`PrinterProtocolTypeTest`、`PrinterDeviceStatusTest`，3 个测试通过。
- [x] T1.2 新增 `PrinterProtocolAdapter` 和 Factory。
  - 目标：统一 `getStatus/pause/resume/cancel/emergencyStop/uploadFile` 能力。
  - 验收：按协议返回唯一 Adapter，未知协议不回退，重复实现有明确检查。
  - 测试：`PrinterProtocolAdapterFactoryTest`，3 个测试通过；协议相关测试共 6 个通过。
- [x] T1.3 实现 `KlipperMoonrakerAdapter`。
  - 目标：封装现有 `MoonrakerApiClient`，转换 Moonraker 状态，分类设备异常。
  - 验收：现有 Klipper 行为不回退，凭据不进入日志/响应。
  - 测试：`KlipperMoonrakerAdapterTest`，3 个测试通过；全量 `mvn test` 共 29 个通过。
- [x] T1.4 将打印机控制改为 Service → Adapter。
  - 目标：移除 `PrinterControlController` 对 Moonraker 的直接依赖。
  - 验收：暂停、急停和后续恢复/取消接口经过统一权限、状态和协议选择。
  - 测试：`PrinterControlServiceTest`、`GlobalExceptionHandlerTest`，7 个针对性测试通过；协议异常已映射为 10001/5004/10003。
- [x] T1.5 将任务服务改为 Adapter 调用。
  - 目标：替换 `PrintJobServiceImpl` 的上传、启动、取消等直接 Moonraker 调用。
  - 验收：设备成功后才更新任务/打印机状态，失败不会伪造 `PRINTING`。
  - 测试：`PrintJobAdapterTest`、`PrintJobOwnershipTest`，5 个针对性测试通过；全量 `mvn test` 共 35 个通过；Adapter 调用已从 `PrintJobController` 和 `PrintJobServiceImpl` 移除。
- [x] T1.6 将监控任务改为 Adapter 调用。
  - 目标：替换 `PrinterMonitorTask` 的直接 Moonraker 查询。
  - 验收：按打印机隔离异常，统一状态写入缓存/数据库；适配器异常会进入离线处理。
  - 测试：`PrinterMonitorAdapterTest` 覆盖适配器查询和异常离线清理；编译及针对性测试通过。
- [x] T1.7 统一固件类型写入和旧数据兼容。
  - 目标：新写入只使用 `KLIPPER/RRF`，兼容历史 `Klipper`。
  - 验收：新增、更新、批量添加和扫描结果均规范化；不支持的协议不会静默回退。
  - 测试：`PrinterServiceFirmwareTypeTest` 覆盖新增和更新写入路径；全量 `mvn test` 共 39 个通过。

## 2. P0 RRF 3.7 适配边界

- [x] T2.1 收集并登记 RRF 3.7 协议证据。
  - 目标：确认状态、认证、暂停/恢复/取消/急停、上传和启动的实际 API。
  - 验收：已记录官方 HTTP/G-code/对象模型资料；未有真实设备证据的字段和副作用仍列为待确认/unsupported。
  - 产物：`../printer-protocol-and-websocket/rrf-3.7-protocol-evidence.md`。
- [x] T2.2 实现 `RrfApiClient` 和 `RrfAdapter` 骨架。
  - 目标：独立于 Moonraker，完成协议选择和统一状态映射。
  - 验收：`RRF` 永不调用 Moonraker，未支持能力返回稳定 `UNSUPPORTED` 错误；Factory 已可选择 RRF Adapter。
  - 测试：`RrfAdapterTest`、`PrinterProtocolAdapterFactoryTest`，6 个针对性测试通过；全量 `mvn test` 共 42 个通过。
- [x] T2.3 根据证据逐项实现 RRF 真实能力。
  - 目标：只实现已确认的 HTTP 调用。
  - 验收：已实现 `rr_connect` 会话、`rr_model` 状态、`rr_gcode` 控制和 `rr_upload` 文件上传；可复现协议测试通过，API_HANDOFF 已标注实机限制。
  - 测试：`RrfApiClientTest` 4 个协议测试通过；全量 `mvn test` 共 46 个通过。尚未宣称真实 RRF 3.7 设备联调完成。

## 3. P0 WebSocket 实时状态

- [x] T3.1 新增统一消息对象和消息类型校验。
  - 目标：`FarmStatusMessage`、`SNAPSHOT`、`PRINTER_STATUS`、`PRINTER_OFFLINE`、`JOB_STATUS`。
  - 验收：`FarmStatusMessage` 固定顶层字段，校验消息类型、时间戳、打印机 ID 和敏感字段；监控任务已改用类型化打印机状态消息。快照、离线和任务事件留到后续 Task。
  - 测试：`FarmStatusMessageTest`、`WebSocketSecurityTest` 共 4 个针对性测试通过。
- [x] T3.2 新增快照服务和连接成功快照。
  - 目标：连接鉴权成功后发送 `data.printers` 全量快照。
  - 验收：鉴权成功后发送一次 `SNAPSHOT`；快照从安全 `PrinterVO` 构建，不依赖单台设备在线，空农场返回空数组，不返回 Entity 敏感字段。
  - 测试：`FarmStatusSnapshotServiceTest`、`FarmStatusMessageTest`、`WebSocketSecurityTest` 共 6 个针对性测试通过。
- [x] T3.3 接入打印机状态和离线事件。
  - 目标：监控任务发布状态变化和离线事件，避免高频重复离线消息。
  - 验收：监控任务通过 `WebSocketEventPublisher` 发布 `PRINTER_STATUS`/`PRINTER_OFFLINE`；相同设备连续离线只通知一次，恢复后重新发布状态；单台设备故障不影响其他设备。
  - 测试：`PrinterMonitorAdapterTest` 3 个测试通过，覆盖适配器调用、离线通知和重复离线抑制。
- [x] T3.4 接入任务状态事件。
  - 目标：任务暂停、启动、完成、失败、取消后发布 `JOB_STATUS`。
  - 验收：任务服务和监控任务均在 `updateById` 成功后发布 `JOB_STATUS`；数据库更新失败不发布成功事件；未绑定打印机的排队任务不构造缺少设备 ID 的消息。
  - 测试：`PrintJobAdapterTest`、`PrintJobOwnershipTest`、`PrinterMonitorAdapterTest` 覆盖任务服务和设备反馈事件路径。
- [x] T3.5 补齐 WebSocket 生命周期测试。
  - 目标：Token、连接上限、快照、四类消息、发送失败和断线清理。
  - 验收：已覆盖 Token 拒绝、有效连接、首帧快照、配置连接上限、广播发送失败清理、断开清理和消息结构校验；真实容器已完成 JWT 握手和首帧快照验收。
  - 测试：`WebSocketSecurityTest`、`FarmStatusMessageTest` 共 9 个针对性测试通过（后续 T9.9 又补充事件/Ping 覆盖）。

## 4. P1 打印机管理

- [x] T4.1 实现 `GET /api/v1/printers/{id}` 和 `PrinterDetailVO`。
  - 验收：返回安全打印机配置、实时状态缓存和当前任务摘要；不存在设备返回 404，未命中状态缓存或未绑定任务返回 `null`，不暴露 `apiKey`。
  - 测试：`PrinterDetailServiceTest` 3 个测试通过。
- [x] T4.2 实现打印机状态历史分页和迁移/持久化方案。
  - 验收：新增 `GET /api/v1/printers/{id}/history`，按设备和时间范围返回统一 `PageResult`；Redis 保留短期高频历史，MySQL 保存状态变化/每分钟样本。
  - 迁移：新增可重复执行的 `06-add-printer-status-history.sql`；已有 Docker 数据卷需备份后手工执行。
- [x] T4.3 实现打印机统计接口。
  - 验收：新增 `GET /api/v1/printers/{id}/statistics`，按任务创建时间范围返回任务数量、成功率和已结束任务时长；无任务时返回零值。
  - 限制：当前不统计数据库没有记录的耗材成本、能耗和真实开机时长。
- [x] T4.4 实现恢复、取消当前设备任务接口。
  - 验收：恢复仅允许绑定任务处于 `PAUSED`，通过协议适配器成功后更新为 `PRINTING` 并发布任务事件；取消复用任务服务完成归属校验、设备调用、解绑和事件发布。
- [x] T4.5 扩展扫描和批量添加的协议识别及逐项结果。
  - 验收：扫描通过 Moonraker/RRF HTTP 探测识别协议，结果写入规范化 `firmwareType`；批量添加保留逐项成功/失败原因。
  - 限制：扫描接口只返回已识别设备，不把整个网段的未响应地址伪装成失败设备。
- [x] T4.5a 统一打印机录入后的初始状态。
  - 验收：新增、重新录入和批量扫描入库在下一次协议探测前写入 `UNKNOWN`，不再产生未登记的 `ONLINE` 状态；普通配置编辑保留设备当前状态。
  - 测试：`PrinterServiceFirmwareTypeTest` 覆盖新增和批量录入状态；状态枚举和 Swagger 描述已同步。
- [x] T4.6 补齐打印机 Controller/权限/设备异常测试。
  - 验收：打印机接口覆盖未登录 401、操作员访问管理员扫描 403、历史分页参数 400；控制服务覆盖设备离线和适配器调用路径。

每个 Task 都必须先更新 API_HANDOFF 的目标契约，再实现 Controller、Service、Mapper/DTO/VO 和测试。

## 5. P1 文件库

- [x] T5.1 修复文件分页名称和材质筛选，并增加查询测试。
  - 验收：显式 Mapper SQL 固定 `fileName -> original_name LIKE`、`materialType -> UPPER(material_type) =`；输入 trim/大写规范化；操作员忽略请求中的 `userId` 并按当前用户隔离。
  - 测试：`PrintFileQueryTest` 2 个测试通过。
- [x] T5.2 实现文件目录树 `GET /api/v1/print-files/tree`。
  - 验收：返回完整树节点 `id,parentId,folder,name,fileSize,materialType,createdAt,children`；操作员仅本人，管理员全部；目录优先、同级创建时间倒序，孤立/循环节点安全按根节点返回。
  - 测试：`PrintFileTreeTest` 2 个测试通过。
- [x] T5.3 实现文件关联任务 `GET /api/v1/print-files/{id}/jobs`。
  - 验收：先校验文件归属，再按 `file_id` 分页返回 `PageResult<PrintJobVO>`；操作员仅本人任务，管理员全部；无文件或无权统一 404。
  - 测试：`PrintJobOwnershipTest` 新增 2 个文件关联任务归属测试，全量测试通过。
- [x] T5.4 实现安全预览 `GET /api/v1/print-files/{id}/preview`。
  - 验收：返回已解析元数据和缩略图，不读/返回 G-code 原文、`safeName`、`rustfsKey`、`fileUrl` 或下载 URL；复用文件归属校验，目录返回 422。
  - 测试：`PrintFileOwnershipTest` 新增本人预览和目录拒绝测试，全量测试通过。
- [x] T5.5 统一 `folder/isFolder` 对外字段并更新 VO、Swagger 和前端契约。
  - 验收：`PrintFileVO` 和 `FileNodeVO` 对外只输出 `folder`；`isFolder` 仅保留在实体/数据库内部，不兼容输出旧字段；未设置目录标记的文件也稳定输出 `folder=false`。`PrintFileVOContractTest` 和真实上传回归均已验证。
  - 测试：`PrintFileVOContractTest` 验证序列化字段，全量测试通过。
- [x] T5.6 明确已关联任务文件删除策略，补充权限和 RustFS 失败测试。
  - 验收：固定为禁止删除；已关联任意任务返回 HTTP 409/业务码 409，批量删除逐项失败，不删除数据库记录；目录返回 422，RustFS 失败返回 5003。
  - 测试：`PrintFileOwnershipTest` 覆盖关联文件、批量逐项结果、目录删除和 RustFS 删除失败路径，全量测试通过。
- [x] T5.7 增加下载 URL 有效期上限和文件存储异常测试。
  - 验收：`expires` 分钟、默认60、服务端默认上限120并支持配置，超过截断；先做文件归属校验，RustFS 签发失败返回 5003。
  - 测试：`PrintFileOwnershipTest` 覆盖上限截断、权限校验和预签名存储异常，全量测试通过。
- [x] T5.8 收敛文件响应中的对象存储内部字段。
  - 验收：`PrintFileVO` 不再返回直连 `fileUrl`；`rustfsKey`、`safeName` 和 `fileUrl` 仅供后端内部使用，前端下载统一调用 `/download` 获取预签名 URL。
  - 测试：`PrintFileVOContractTest` 和 `SensitiveFieldSerializationTest` 覆盖字段脱敏。
- [x] T5.9 冻结文件 VO 的字段名和数值单位。
  - 验收：预计打印时长对外字段固定为 `estTime`（Integer，秒），耗材重量/长度固定为 `BigDecimal`（克/米）；不再使用文档中的 `estimatedSeconds` 别名。
  - 测试：`PrintFileVOContractTest` 验证实际 JSON 字段名。

## 6. P1 打印任务

- [x] T6.1 实现标准创建接口 `POST /api/v1/print-jobs`，旧 `/create` 标记 deprecated。
  - 验收：复用现有创建 Service，接收 `fileId,priority,printerId?`；不指定设备创建 `QUEUED`，指定设备按 T6.2 规则进入 `ASSIGNED`；旧地址已标记 Java/OpenAPI deprecated。
  - 测试：`SecurityResponseTest` 覆盖标准创建地址未认证 401，全量测试通过。
- [x] T6.2 支持可选 `printerId`，不指定时进入 `QUEUED`。
  - 验收：指定设备时复用安全派发逻辑，校验设备存在且 IDLE，成功进入 `ASSIGNED` 并绑定设备但不直接打印；失败事务回滚。
  - 测试：`PrintJobCreateTest` 覆盖无设备排队和指定空闲设备派发路径，全量测试通过。
- [x] T6.3 实现重试接口并复用状态机、归属和设备规则。
  - 验收：仅 `FAILED` 可重试；保留文件/用户/优先级，清除设备、操作员、时间和错误信息，进度归零后回到 `QUEUED` 并推送事件，不调用设备。
  - 测试：`PrintJobOwnershipTest` 覆盖失败任务重试清理和非失败状态拒绝，全量测试通过。
- [x] T6.4 实现重新排队接口。
  - 验收：仅 `ASSIGNED/READY` 可重新排队；解除设备绑定并清理运行字段后回 `QUEUED`，不调用设备；`PRINTING/PAUSED/FAILED` 等状态拒绝。
  - 测试：`PrintJobOwnershipTest` 覆盖设备释放和暂停任务拒绝，全量测试通过。
- [x] T6.5 实现优先级修改接口。
  - 验收：接收 JSON `{priority:0-100}`，仅 `QUEUED` 且当前用户可见的任务可修改；其他状态 422，不调用设备。
  - 测试：`PrintJobOwnershipTest` 覆盖排队任务修改和已派发任务拒绝，全量测试通过。
- [x] T6.6 将取消逻辑从 Controller 迁移到 Service。
  - 验收：Controller 仅委托 `PrintJobService.cancelJob`；Service 统一处理归属、状态、适配器取消、解绑、持久化和事件；现有 `PrintJobAdapterTest`、`PrinterControlServiceTest` 已覆盖设备调用路径。
- [x] T6.7 增加文件摘要、打印机摘要或冻结前端组合查询方案。
  - 验收：冻结前端组合查询方案；`PrintJobVO` 使用 `fileId/printerId`，文件调用 `/print-files/{fileId}/preview`，打印机调用 `/printers/{printerId}`，排队任务不查询空 `printerId`。
- [x] T6.8 补齐任务状态事件、权限和端到端测试。
  - 验收：已覆盖 Service 状态/归属/设备调用、任务路由认证和绑定任务 `JOB_STATUS` 事件；队列任务因无 `printerId` 不构造消息；真实 MySQL/Redis/RustFS/打印机端到端链路保留现场验收。

## 7. P1 认证与用户

- [x] T7.1 实现 `GET /api/v1/auth/me`。
  - 验收：从 JWT 当前用户 ID 查询脱敏资料，不接收路径用户 ID；未认证返回 HTTP 401、业务码 `401`，旧 profile 地址继续兼容。
  - 测试：`SecurityResponseTest` 覆盖未认证访问；全量 `mvn test` 通过。
- [x] T7.2 用户分页、资料和管理响应脱敏复核。
  - 验收：资料和管理员分页统一返回 `UserVO`，不直接序列化 `User`，响应字段不包含 `passwordHash`。
  - 测试：`SensitiveFieldSerializationTest` 覆盖 `UserVO` 脱敏；全量 `mvn test` 通过。
- [x] T7.3 补充管理员创建、启用、禁用、角色修改的 Controller 集成测试。
  - 验收：ADMIN 成功委托四类管理操作；OPERATOR 调用四类 ADMIN-only 路由统一返回 HTTP 403、业务码 `403`。
  - 测试：`SecurityResponseTest` 覆盖成功和拒绝路径；全量 `mvn test` 通过。
- [x] T7.4 统一登录失败次数、Redis 锁定、禁用用户和 Token 错误响应。
  - 验收：账号/密码失败和锁定为 HTTP 401、业务码 `401`；禁用用户为 HTTP 403、业务码 `403`；无效/过期 Token 为 HTTP 401；Redis 故障沿用 HTTP 503、业务码 `5002`。
  - 测试：`UserAuthenticationTest`、`SecurityResponseTest` 覆盖认证失败、锁定、禁用 Token 和无效 Token；全量 `mvn test` 通过。
- [x] T7.5 评估是否实现 logout；第一版不实现服务端 logout。
  - 验收：交接文档明确没有 `/auth/logout`；前端退出时删除 Token、清空用户状态并断开 WebSocket，Token 自然过期。
- [x] T7.6 收紧管理员密钥传输方式。
  - 验收：密码迁移和状态检查只接受 `X-Admin-Secret` 请求头，拒绝 URL 查询参数；使用常量时间比较并有缺失/成功请求测试。

## 8. P1 前端接入与联调

- [ ] T8.1 在真实前端仓库配置 API/WS 地址和环境变量。
- [ ] T8.2 封装 HTTP 客户端、Bearer Token、统一响应和错误处理。
- [ ] T8.3 完成登录、角色菜单、用户管理和个人资料。
- [ ] T8.4 完成打印机看板、设备管理和 WebSocket 增量更新。
- [ ] T8.5 完成文件库、上传、目录、下载、删除和预览。
- [ ] T8.6 完成任务队列、安全打印、控制、重试和状态展示。
- [ ] T8.7 记录每个真实联调接口、请求样例、响应样例和前端文件路径。

当前后端仓库没有前端工程；在找到实际前端目录前，不修改不存在的前端文件。

## 9. P2 测试、迁移和运维

- [x] T9.1 补齐核心 Controller 和权限集成测试。
  - 验收：认证、打印机、文件、任务、用户管理核心入口覆盖匿名 401；ADMIN-only 的打印机/用户管理操作覆盖 OPERATOR 403；管理员用户管理成功委托路径已覆盖。
  - 测试：`SecurityResponseTest` 当前 31 个测试通过，覆盖 401、403、404、500 等 HTTP 响应；真实数据库成功链路仍需容器联调。
- [x] T9.2 补齐 Mapper/MySQL 查询、分页和迁移验证。
  - 验收：`farm.sql` 与当前实体/Mapper 字段已核对；02 增量脚本按列/索引存在性重复执行安全；04 状态迁移、05 协议规范化和 06 历史表脚本可重复执行。
  - 测试：`PrintFileMapperTest` 3 项通过，覆盖文件筛选/分页、目录权限、任务关联分页和任务计数条件；2026-09-03 已备份现有 Docker 数据卷并执行 02–06 迁移，真实 MySQL 查询冒烟通过（46 台打印机、1 个文件、3 个任务）；完整索引执行计划仍待现场验收。
  - 说明：项目未引入 Flyway，已有 Docker 数据卷仍需备份后手工执行增量 SQL。
- [x] T9.3 补齐 Redis 锁、缓存和登录保护测试。
  - 验收：登录失败计数、15 分钟锁定、禁用标记、打印机状态缓存 10 秒 TTL、状态锁 5 秒 TTL 和锁竞争路径均有测试；不连接或清空真实 Redis。
  - 测试：`RedisProtectionTest`、`PrinterCacheRedisTest`；全量 `mvn test` 通过。
- [x] T9.4 补齐 RustFS 上传、预签名 URL、删除失败测试。
  - 验收：客户端成功委托和 `StorageException` 转换有测试；文件 Service 继续校验归属、预签名 URL 上限和任务引用删除保护；不连接真实 RustFS。
  - 测试：`RustFsClientTest`、`PrintFileOwnershipTest`；全量 `mvn test` 通过。2026-09-03 使用临时 G-code 通过真实 RustFS 容器完成上传、预览、预签名下载 URL 和删除，清理后文件记录数量恢复为 1。
- [x] T9.5 补齐 Klipper/RRF Adapter 和 Mock 测试。
  - 验收：统一 Factory、Klipper/RRF Adapter、状态映射、RRF HTTP 会话/状态/G-code/上传 Mock 均有测试；真实设备联调保留现场验收。
  - 测试：`KlipperMoonrakerAdapterTest`、`RrfAdapterTest`、`RrfApiClientTest`、协议 Factory/Detector/Type 测试；全量 `mvn test` 通过。
- [ ] T9.6 完成上传文件到打印完成的端到端测试。
  - 说明：必须在真实 MySQL、Redis、RustFS 和至少一台 Klipper/RRF 设备环境执行；当前环境无 Docker socket 和真实打印机，保留现场验收。
- [x] T9.7 核对实体、Mapper、`farm.sql` 和增量 SQL 字段一致性。
  - 验收：已核对 `User`、`Printer`、`PrintFile`、`PrintJob`、`PrinterStatusHistory` 与对应 Mapper、`farm.sql`、`02` 和 `06` 脚本；历史 `V*.sql` 仅作为手工迁移记录，不会被 Spring 自动执行。
  - 结论：新字段 `operator_id`、文件目录/对象存储字段、`is_safe_to_print` 和状态历史表由增量脚本补齐；状态值、固件类型由 `04`、`05` 规范化。2026-09-03 已在现有 Docker 数据卷完成备份后执行 02–06，并核对记录数量未变化、任务状态已无 `MANUAL`、协议类型已无旧值；完整端到端链路仍待真实设备。
- [x] T9.8 增加健康检查、启动依赖和生产运维说明。
  - 验收：`/actuator/health` 免认证且不公开详情；Compose 依赖、生产密钥、备份、迁移和无设备运行要求已记录在 `OPERATIONS.md`。
  - 测试：`SecurityResponseTest` 覆盖健康探针不返回 401/403（当前 31 项）；`ProductionSafetyValidatorTest` 覆盖生产必填密钥、默认密钥、CORS 和 Swagger 开关；全量 `mvn test` 通过。
- [ ] T9.9 完善 WebSocket 重连、设备离线告警和任务失败告警。
  - 后端完成：离线/恢复事件抑制与发布、失败任务 `JOB_STATUS.errorReason`、连接失败清理均已有实现和测试。
  - 本 Task 补充：服务端按 30 秒可配置间隔发送协议级 Ping 保活，发送失败清理会话；修复独立 WebSocket `ObjectMapper` 未注册 Java 时间模块导致 `SNAPSHOT` 发送失败的问题，并增加 `LocalDateTime` 回归测试；`WebSocketSecurityTest` 当前 9 项通过，业务消息仍只保留四种冻结类型。
  - 真实容器验收：2026-09-03 使用 Node WebSocket 客户端完成 JWT 握手，收到 `SNAPSHOT`（46 台打印机，时间戳有效，无 `apiKey/rustfsKey`）；前端自动重连和告警展示仍待真实前端仓库。
  - 待前端：自动重连和指数退避；当前仓库没有真实前端工程，不能在此完成前端文件改动。

## 10. 第一版最终验收

- [ ] T10.1 ADMIN 可创建和管理 OPERATOR。
- [ ] T10.2 ADMIN 可添加 Klipper 或 RRF 打印机。
- [ ] T10.3 ADMIN/OPERATOR 可查看设备状态和文件库。
- [ ] T10.4 用户可上传文件、创建任务、派发、安全确认并启动。
- [ ] T10.5 支持暂停、恢复、取消和急停。
- [ ] T10.6 REST 与 WebSocket 状态正确同步。
- [ ] T10.7 设备离线不会持续刷异常日志或拖垮监控。
- [ ] T10.8 文件、任务、用户和设备权限由后端校验。
- [ ] T10.9 数据库状态和接口返回结构完成迁移/冻结。
- [ ] T10.10 Klipper/RRF 均通过适配器接入，核心链路有自动化测试。
