# Farm 开发 TODO

版本：v1.0  
依据：[API_HANDOFF.md](./API_HANDOFF.md) 与当前 Java 源码  
更新时间：2026-09-02

## 使用说明

- `[x]` 已完成。
- `[ ]` 未完成。
- `P0`：阻塞前后端联调或存在安全/数据风险，优先完成。
- `P1`：第一版农场管理功能需要完成。
- `P2`：稳定性、体验和生产环境加固。
- Kiro 执行入口：[PROJECT.md](./PROJECT.md)。执行顺序为 `requirements.md` → `design.md` → `tasks.md` → 单个 Task 实现 → 测试/验收 → 更新任务状态 → 下一个 Task。
- 项目级规格位于 `.kiro/specs/farm-v1/`；协议适配和 WebSocket 的详细规格位于 `.kiro/specs/printer-protocol-and-websocket/`。

当前仓库只有 Java 后端，没有 Vue、React 或其他前端工程。前端部分记录页面和联调任务，不填写不存在的前端文件路径。

## 当前基线

- [x] 已完成源码接口盘点和前后端交接文档：`API_HANDOFF.md`
- [x] 已确定角色：`ADMIN`、`OPERATOR`
- [x] 已移除产品设计中的 `CUSTOMER` 角色方向
- [x] 已确定打印机协议类型：`KLIPPER`、`RRF`
- [x] 已确认当前设备实现仍是 Klipper/Moonraker
- [x] 已确认开发环境默认端口为 `8080`
- [x] 已确认开发环境默认关闭打印机监控任务：`farm.tasks.enabled=false`
- [x] 已执行 `mvn test`：1 个上下文测试通过
- [ ] 增加真实 HTTP 接口和权限测试
- [ ] 增加真实 Klipper/RRF 设备测试

## P0：先修复契约和安全阻塞项

### P0.1 统一响应和错误处理

- [x] 修复 `GlobalExceptionHandler` 丢失 `BusinessException.code` 的问题。
  - 位置：`src/main/java/com/example/farm/common/exception/GlobalExceptionHandler.java`
  - 验收：打印机离线返回 `code=10001`；参数错误返回 `code=400`；资源不存在返回 `code=404`。
- [x] 增加统一的认证失败处理器。
  - JWT 无效/过期返回 HTTP 401、JSON `code=401`。
  - 未登录访问受保护接口返回 HTTP 401、统一 `Result`。
- [x] 增加统一的权限失败处理器。
  - ADMIN-only 接口由 OPERATOR 调用时返回 HTTP 403、JSON `code=403`。
- [x] 为资源不存在、状态冲突、设备离线、设备网络错误建立明确的异常映射。
- [x] 保持成功响应格式：`{code:200,message,data,timestamp}`。
- [x] 增加异常处理器测试，覆盖核心的 401、403、409、422、503 映射；404/500 仍需补充端到端测试。

### P0.2 统一分页

- [x] 增加统一分页返回 DTO：

```json
{
  "records": [],
  "total": 0,
  "pageNum": 1,
  "pageSize": 20,
  "pages": 0
}
```

- [x] 统一打印机、文件、任务、用户分页接口的返回结构。
- [x] Service 层继续使用 MyBatis-Plus，Controller 对外统一为 `PageResult`，前端使用 `pageNum/pageSize`。
- [x] 为 `pageNum`、`pageSize` 增加正数和最大值限制，`pageSize <= 100`。

### P0.3 统一状态机

- [x] 任务初始状态统一为 `QUEUED`，修复创建任务与调度器查询状态不一致的问题。
- [x] 任务状态统一为：

```text
QUEUED, ASSIGNED, READY, PRINTING, PAUSED,
COMPLETED, FAILED, CANCELLED
```

- [x] 删除业务代码中的旧状态判断，增加兼容读取和一次性数据迁移脚本 `04-normalize-print-job-status.sql`。
- [x] `PREPARING` 只作为打印机状态，不作为任务状态。
- [x] 明确并测试状态流转：

```text
QUEUED -> ASSIGNED -> READY -> PRINTING
PRINTING <-> PAUSED
QUEUED/ASSIGNED/READY/PAUSED -> CANCELLED
PRINTING -> COMPLETED/FAILED
FAILED -> QUEUED（重试）
```

设备上报取消时允许 `PRINTING -> CANCELLED`；用户主动取消打印中的任务仍由当前控制接口拒绝。

- [x] 禁止任务调度器在真实调用设备前把任务写成已开始打印；调度只进入 `ASSIGNED`，设备调用成功后才进入 `PRINTING`。
- [x] 为每个非法状态转换返回 HTTP 422。
- [x] 为状态流转增加单元测试，覆盖旧状态兼容、正常流转和非法流转。

### P0.4 资源归属和权限

- [x] 文件查询、文件夹查询、文件删除、文件下载统一执行用户归属校验；ADMIN 可管理全部文件，OPERATOR 只能访问自己的文件。
- [x] 新建文件夹必须写入当前登录用户的 `userId`，父目录也必须可访问且确实为文件夹。
- [x] 操作员不能通过 `userId` 查询其他用户的任务；OPERATOR 的任务查询强制使用当前 JWT 用户 ID。
- [x] 操作员不能取消其他用户的任务；本地农场采用“操作员管理自己的任务、管理员管理全部任务”的边界，并增加权限测试。
- [x] 创建任务时校验文件存在、不是文件夹，并且属于当前用户或当前用户是管理员。
- [x] `operatorId` 不信任前端传值，统一取当前 JWT 用户；当前管理员代操作记录管理员本人 ID。
- [x] 任务取消、派发、启动等现有操作统一校验任务资源归属和状态；重试、重新排队接口尚未实现，待接口落地时复用同一规则。
- [x] WebSocket 握手必须携带 JWT，缺少或无效 Token 的连接被拒绝；当前仍为农场级广播。

### P0.5 输入校验

- [x] `PrinterAddDTO`、`PrinterUpdateDTO` 增加名称、IP、MAC、固件类型、网格位置校验。
- [x] `PrintJobCreateDTO` 增加 `fileId @NotNull`、`priority` 范围校验。
- [x] `AssignJobRequest` 增加 `jobId`、`printerId` 非空和正数校验。
- [x] `ConfirmSafeRequest`、`StartPrintJobRequest` 增加 ID 和 action 枚举校验。
- [x] `CreateFolderRequest` 增加名称长度、非法字符、父目录校验。
- [x] 批量添加和批量删除增加最大数量限制，并返回每一项失败原因。
- [x] 上传文件校验扩展名、文件大小和文件名；允许类型绑定到 `farm.file.allowed-types` 和 `farm.file.max-file-size`。
- [x] 统一处理文件不存在、空文件、超限文件和 RustFS 上传失败。

### P0.6 敏感字段和生产配置

- [x] 新增 `PrinterVO`、`PrintFileVO`、`PrintJobVO`，禁止接口直接返回 Entity。
- [x] 打印机响应不得返回明文 `apiKey`。
- [x] 文件响应不得返回 RustFS 内部 `rustfsKey`。
- [x] 生产环境强制要求 `JWT_SECRET_KEY`、`ADMIN_SECRET_KEY`、MySQL、Redis、RustFS 密钥，不允许使用开发默认值。
- [x] CORS 不再使用生产环境的 `* + credentials=true`。
- [x] 生产环境按配置关闭 Swagger 或限制到可信局域网来源。
- [x] 检查日志中不输出密码、JWT、API Key、数据库密码和 RustFS 密钥。

## P0：打印机协议适配基础

- [x] 新增统一内部接口 `PrinterProtocolAdapter`。
- [x] 定义统一方法：

```text
getStatus()
pause()
resume()
cancel()
emergencyStop()
uploadFile()
startPrint()
```

- [x] 新增 `KlipperMoonrakerAdapter`，封装当前 `MoonrakerApiClient`。
- [x] 将 `PrinterControlController` 中直接调用 `MoonrakerApiClient` 的代码改为调用 Service/Adapter。
- [x] 将 `PrintJobServiceImpl` 中的上传、启动、取消设备调用改为 Adapter。
- [x] 将 `PrinterMonitorTask` 的状态查询改为 Adapter，并将适配器异常纳入离线处理。
- [x] `firmwareType` 入库值统一为大写 `KLIPPER`、`RRF`，兼容旧数据 `Klipper`；新增迁移脚本 `05-normalize-printer-firmware-type.sql`。
- [x] 已根据官方协议实现 RRF HTTP 会话、状态、G-code 控制和文件上传；仍需真实设备联调确认具体版本、运行模式、存储路径和宏副作用。
- [x] 不在 Moonraker 客户端中通过替换 URL 假装支持 RRF。

## P0：WebSocket 实时状态

- [x] 正式地址统一为 `/ws/farm-status`；必要时短期兼容 `/ws`。
- [x] 增加连接 Token 校验。
- [x] 增加连接上限、异常断开和清理机制；心跳机制仍待补充。
- [x] 统一消息结构：

```json
{
  "type": "PRINTER_STATUS",
  "printerId": 403,
  "timestamp": 1756790000000,
  "data": {}
}
```

- [x] 冻结消息类型并增加服务端校验：`SNAPSHOT`、`PRINTER_STATUS`、`PRINTER_OFFLINE`、`JOB_STATUS`。
- [x] 客户端鉴权连接后发送一次全量 `SNAPSHOT`，数据使用安全打印机 VO。
- [x] 打印机离线时发送 `PRINTER_OFFLINE`，并按设备抑制连续重复离线消息。
- [ ] 明确是否需要按打印机订阅；第一版可先广播全部设备，但必须通过权限校验。
- [x] 增加 WebSocket 真实连接、格式、断线和离线推送基础测试；当前已有消息对象、快照服务和监控事件单元测试，真实容器级网络测试仍待补充。

## P1：后端第一版功能

### P1.1 打印机管理

- [x] 实现 `GET /api/v1/printers/{id}` 打印机详情。
  - 返回安全配置、实时状态缓存、当前任务摘要和协议类型；不返回 `apiKey`。
- [x] 实现 `GET /api/v1/printers/{id}/history` 状态历史分页。
  - Redis 保留高频短期历史，MySQL 增量保存状态变化和每分钟样本；迁移脚本为 `06-add-printer-status-history.sql`。
- [x] 实现 `GET /api/v1/printers/{id}/statistics` 打印统计。
  - 按任务创建时间聚合任务数量、成功率和已结束任务时长；暂不虚构耗材成本等数据库未记录指标。
- [x] 实现 `POST /api/v1/control/{id}/resume` 恢复打印。
- [x] 实现 `POST /api/v1/control/{id}/cancel` 取消当前设备任务。
- [ ] 明确设备离线、忙碌、错误时的 HTTP 和业务错误码。
- [x] 扫描接口支持协议识别，不再只扫描 Moonraker 7125。
  - 通过 Moonraker `/server/info` 和 RRF `/rr_connect` 识别 `KLIPPER/RRF`；未知设备不进入结果。
- [x] 扫描和批量添加返回每个设备的成功/失败原因。
  - 批量添加已返回逐项结果；扫描结果对每个已识别设备返回协议、MAC 和新旧状态，未识别地址不作为设备结果。
- [x] 补齐打印机 Controller/权限/设备异常测试。
  - 已覆盖控制接口 401、扫描接口 403、历史分页参数 400，以及控制服务适配器路径。

### P1.2 文件库

- [x] 修复现有文件分页的 `fileName`、`materialType` 筛选不生效问题。
  - 已通过显式 Mapper SQL 固定 `fileName -> original_name LIKE`、`materialType -> UPPER(material_type) =`，并对输入 trim/大写规范化；操作员继续强制按 `user_id` 隔离。
- [x] 实现 `GET /api/v1/print-files/tree` 目录树。
  - 已实现完整树返回 `id,parentId,folder,name,fileSize,materialType,createdAt,children`；操作员仅本人，管理员全部；目录优先、同级创建时间倒序，孤立/循环节点安全按根节点返回。
- [x] 实现 `GET /api/v1/print-files/{id}/jobs` 文件关联任务。
  - 已先校验文件归属，再按 `file_id` 分页返回 `PageResult<PrintJobVO>`；操作员仅本人任务，管理员全部；无文件或无权统一 404。
- [x] 实现 `GET /api/v1/print-files/{id}/preview` 安全预览信息。
  - 已返回已解析元数据和缩略图，不读/返回 G-code 原文、`safeName`、`rustfsKey`、`fileUrl` 或下载 URL；复用文件归属校验，目录返回 422。
- [x] 统一 `folder` 布尔字段名称，避免 `isFolder` 序列化差异。
  - 已统一 `PrintFileVO` 和 `FileNodeVO` 对外只输出 `folder`；`isFolder` 仅保留在实体/数据库内部，不兼容输出旧字段。
- [x] 对已关联打印任务的文件删除给出明确策略：禁止删除或软删除。
  - 已固定为禁止删除；已关联任意任务返回 HTTP 409/业务码 409，批量删除逐项失败，不删除数据库记录；目录返回 422，RustFS 失败返回 5003。
- [x] 下载接口继续返回预签名 URL，但增加过期时间上限和权限校验。
  - 已实现 `expires` 分钟、默认60、服务端默认上限120并支持配置，超过截断；先做文件归属校验，RustFS 签发失败返回 5003。
- [ ] 明确 RustFS 文件不存在、URL 过期和删除失败的前端提示。

### P1.3 打印任务

- [x] 实现标准创建接口 `POST /api/v1/print-jobs`。
  - 已复用现有创建 Service，接收 `fileId,priority`，创建 `QUEUED` 任务；`printerId` 留给后续 T6.2。
- [x] 保留 `/api/v1/print-jobs/create` 作为兼容接口，并在 Swagger 标记 deprecated。
  - 两条地址共用同一 Controller 创建逻辑，旧地址已标记 Java/OpenAPI deprecated。
- [x] 创建任务支持可选 `printerId`；不指定时进入 `QUEUED`。
  - 已指定设备时复用安全派发逻辑，校验设备存在且 IDLE，成功进入 `ASSIGNED` 并绑定设备但不直接打印；失败事务回滚。
- [x] 实现 `POST /api/v1/print-jobs/{id}/retry`。
  - 已仅允许 `FAILED` 重试；保留文件/用户/优先级，清除设备、操作员、时间和错误信息，进度归零后回到 `QUEUED` 并推送事件，不调用设备。
- [x] 实现 `POST /api/v1/print-jobs/{id}/requeue`。
  - 已仅允许 `ASSIGNED/READY` 重新排队；解除设备绑定并清理运行字段后回 `QUEUED`，不调用设备；`PRINTING/PAUSED/FAILED` 等状态拒绝。
- [ ] 实现 `PUT /api/v1/print-jobs/{id}/priority`。
- [ ] 将取消逻辑从 Controller 移到 Service，统一权限、状态和设备调用。
- [ ] 安全打印流程固定为：派发 -> 安全确认 -> 启动。
- [ ] 启动时由后端记录真实操作员，不接受任意前端 `operatorId`。
- [ ] 增加任务详情中的文件摘要和打印机摘要，或明确由前端分别查询。
- [x] 任务派发、启动、完成、失败、取消、暂停等状态在持久化成功后通过 WebSocket 推送 `JOB_STATUS`。

### P1.4 认证与用户

- [ ] 增加当前用户接口，例如 `GET /api/v1/auth/me`，减少前端依赖路径参数。
- [ ] 可选增加 `POST /api/v1/auth/logout`；第一版可通过前端删除 Token 实现退出。
- [ ] 用户分页响应脱敏，禁止返回 `passwordHash`。
- [ ] 增加管理员创建操作员、禁用、启用、修改角色的接口测试。
- [ ] 检查登录失败次数、Redis 锁定和禁用用户的统一错误响应。

## P1：前端页面和联调任务

以下任务对应当前产品需要的客户端功能。前端工程不在本仓库，完成时应在前端项目中记录实际文件路径。

### P1.5 前端基础层

- [ ] 配置 API 基础地址：`http://<server-host>:8080/api/v1`。
- [ ] 配置 WebSocket 地址：`ws://<server-host>:8080/ws/farm-status`。
- [ ] 封装 HTTP 客户端，统一添加 Bearer Token。
- [ ] 统一解析 `{code,message,data,timestamp}`。
- [ ] 处理 401：清除 Token 并跳转登录页。
- [ ] 处理 403、404、409、422、10001、10002、5003、5004。
- [ ] 封装分页组件，兼容后端迁移前的 `current/size` 和目标 `pageNum/pageSize`。
- [ ] 所有 ID、时间、文件大小、进度字段按交接文档处理。

### P1.6 登录和权限页面

- [ ] 登录页：用户名、密码、登录失败、锁定提示。
- [ ] 根据 `role` 控制菜单和按钮展示。
- [ ] 仅 ADMIN 展示用户管理、打印机配置、扫描和删除按钮。
- [ ] ADMIN 用户管理：分页、搜索、创建操作员、禁用、启用、修改密码/角色。
- [ ] 当前用户资料和修改密码页面。
- [ ] 前端密码规则提示必须与后端 6-20 位、大小写和数字规则一致。

### P1.7 打印机看板和管理

- [ ] 打印机网格看板：名称、编号、IP、协议、状态、温度、当前任务和进度。
- [ ] 使用 REST `/printers/page` 获取初始数据。
- [ ] 使用 WebSocket 接收状态更新，连接后等待 `SNAPSHOT`。
- [ ] 显示 `OFFLINE`、`IDLE`、`PREPARING`、`PRINTING`、`PAUSED`、`ERROR`、`UNKNOWN`。
- [ ] 设备离线时显示重连/检查提示，不把离线当成 Redis 缺少数据。
- [ ] ADMIN 打印机新增、编辑、删除、扫描、批量添加。
- [ ] 打印机网格位置编辑和保存。
- [ ] 打印机详情页预留历史、统计、协议类型区域。
- [ ] 暂停、恢复、取消、急停按钮根据状态禁用，并二次确认急停。

### P1.8 文件库

- [ ] 文件列表分页、名称搜索、材质筛选。
- [ ] G-code 上传进度、大小限制、类型错误、RustFS 错误提示。
- [ ] 文件夹创建、进入、返回上级和目录树。
- [ ] 文件下载使用后端返回的预签名 URL。
- [ ] 单个删除和批量删除前确认。
- [ ] 文件详情展示切片参数、耗材、喷嘴和预估时间。
- [ ] 文件关联任务入口预留。
- [ ] 不展示 `apiKey`、`rustfsKey` 等后端内部字段。

### P1.9 任务队列和打印流程

- [ ] 任务队列按 `priority` 和创建时间展示。
- [ ] 新建任务选择文件和可选打印机。
- [ ] 任务状态使用冻结后的 8 个状态，不再显示 `PENDING`、`CANCELED`。
- [ ] 手动派发：选择空闲打印机。
- [ ] 安全确认：现场操作员确认热床/平台安全。
- [ ] 启动打印：只有安全确认后允许启动。
- [ ] 任务详情显示文件、打印机、发起人、操作员、进度和错误原因。
- [ ] 支持取消、重试、重新排队和调整优先级的 UI 预留。
- [ ] WebSocket 收到 `JOB_STATUS` 后更新任务列表，不重复请求整个页面。

## P2：稳定性、运维和体验

### P2.1 测试

- [ ] Controller 测试：登录、打印机、文件、任务核心接口。
- [ ] 权限测试：匿名、ADMIN、OPERATOR 的 401/403。
- [ ] Service 测试：状态机、资源归属、重复操作、设备异常。
- [ ] Mapper 测试：MySQL 关键查询和分页筛选。
- [ ] RustFS 测试：上传、预签名 URL、删除失败。
- [ ] Redis 测试：锁、状态缓存、登录失败保护。
- [ ] WebSocket 测试：连接、快照、状态推送、离线、断开清理。
- [x] 适配器测试：Klipper 模拟响应、RRF 模拟响应、统一状态映射；RRF HTTP 会话/状态/G-code/上传 Mock 测试已补充。
- [ ] 端到端测试：上传文件 -> 创建任务 -> 派发 -> 安全确认 -> 启动 -> 完成。

### P2.2 数据库和迁移

- [ ] 核对 `farm.sql`、增量 SQL、实体和 Mapper 的字段一致性。
- [ ] 为任务状态统一增加可重复执行的迁移脚本。
- [ ] 为旧的 `CANCELED`、`PENDING` 数据提供迁移策略。
- [ ] 明确已有 Docker 数据卷升级步骤，升级前备份 MySQL、Redis 和 RustFS。
- [ ] 不依赖 Spring 自动执行历史 `V*.sql` 文件；当前项目没有 Flyway 依赖。

### P2.3 生产环境

- [ ] 检查 MySQL、Redis、RustFS 健康检查和启动依赖。
- [ ] 增加应用健康检查和依赖状态检查。
- [ ] 配置生产日志级别、日志滚动和敏感信息脱敏。
- [ ] 限制 CORS 到实际前端客户端地址。
- [ ] 限制 Swagger/OpenAPI 访问范围。
- [ ] 配置 JWT 密钥轮换和管理员密钥保管方式。
- [ ] 增加文件存储容量、清理和备份策略。
- [ ] 增加设备离线告警和任务失败告警。

### P2.4 前端体验

- [ ] WebSocket 自动重连和指数退避。
- [ ] 设备离线、忙碌、网络错误、任务失败分别显示不同提示。
- [ ] 删除、急停、取消打印增加二次确认。
- [ ] 任务状态变更增加操作记录提示。
- [ ] 文件上传支持取消和失败重试。
- [ ] 大屏在 WebSocket 断开时显示“数据可能不是最新”。
- [ ] 空状态、加载状态、权限不足和服务端维护状态完善。

## 依赖顺序

```text
统一错误/分页/权限
        ↓
统一任务状态与资源归属
        ↓
PrinterProtocolAdapter
        ↓
打印机控制、任务调度、RRF 接入
        ↓
WebSocket 快照/离线/任务消息
        ↓
前端完整联调和端到端测试
```

## 第一版完成定义

第一版不追求 SaaS 化，只满足局域网单农场稳定运行：

- [ ] ADMIN 可以创建和管理 OPERATOR。
- [ ] ADMIN 可以添加 Klipper 或 RRF 打印机。
- [ ] ADMIN/OPERATOR 可以查看设备状态和文件库。
- [ ] 用户可以上传 G-code、创建任务、派发任务并安全启动。
- [ ] 可以暂停、恢复、取消和急停。
- [ ] 任务状态和打印机状态通过 REST + WebSocket 正确同步。
- [ ] 打印机离线不会导致定时任务持续刷异常日志。
- [ ] 文件、任务、用户和设备权限经过后端校验。
- [ ] 新旧数据库状态完成迁移，接口返回结构稳定。
- [ ] Klipper 与 RRF 均通过适配器接入，而不是在业务层写协议分支。
- [ ] 核心接口、权限、状态机和 WebSocket 有自动化测试。
