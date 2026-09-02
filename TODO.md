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

- [ ] 修复 `GlobalExceptionHandler` 丢失 `BusinessException.code` 的问题。
  - 位置：`src/main/java/com/example/farm/common/exception/GlobalExceptionHandler.java`
  - 验收：打印机离线返回 `code=10001`；参数错误返回 `code=400`；资源不存在返回 `code=404`。
- [ ] 增加统一的认证失败处理器。
  - JWT 无效/过期返回 HTTP 401、JSON `code=401`。
  - 未登录访问受保护接口返回 HTTP 401、统一 `Result`。
- [ ] 增加统一的权限失败处理器。
  - ADMIN-only 接口由 OPERATOR 调用时返回 HTTP 403、JSON `code=403`。
- [ ] 为资源不存在、状态冲突、设备离线、设备网络错误建立明确的异常映射。
- [ ] 保持成功响应格式：`{code:200,message,data,timestamp}`。
- [ ] 增加异常处理器测试，覆盖 400、401、403、404、409、422、500、503。

### P0.2 统一分页

- [ ] 增加统一分页返回 DTO：

```json
{
  "records": [],
  "total": 0,
  "pageNum": 1,
  "pageSize": 20,
  "pages": 0
}
```

- [ ] 统一打印机、文件、任务、用户分页接口的返回结构。
- [ ] 后端兼容当前 MyBatis-Plus 的 `current/size`，迁移完成后前端只使用 `pageNum/pageSize`。
- [ ] 为 `pageNum`、`pageSize` 增加正数和最大值限制，建议 `pageSize <= 100`。

### P0.3 统一状态机

- [ ] 任务初始状态统一为 `QUEUED`，修复当前创建任务使用 `PENDING`、调度器查询 `QUEUED` 的冲突。
- [ ] 任务状态统一为：

```text
QUEUED, ASSIGNED, READY, PRINTING, PAUSED,
COMPLETED, FAILED, CANCELLED
```

- [ ] 删除业务代码中的 `PENDING`、`CANCELED` 状态判断，增加兼容读取或一次性数据迁移。
- [ ] `PREPARING` 只作为打印机状态，不作为任务状态。
- [ ] 明确并测试状态流转：

```text
QUEUED -> ASSIGNED -> READY -> PRINTING
PRINTING <-> PAUSED
QUEUED/ASSIGNED/READY/PAUSED -> CANCELLED
PRINTING -> COMPLETED/FAILED
FAILED -> QUEUED（重试）
```

- [ ] 禁止任务调度器在真实调用设备前把任务写成已开始打印。
- [ ] 为每个非法状态转换返回 HTTP 422。
- [ ] 为状态流转增加 Service 单元测试。

### P0.4 资源归属和权限

- [ ] 文件查询、文件夹查询、文件删除、文件下载统一执行用户归属校验。
- [ ] 新建文件夹必须写入当前登录用户的 `userId`。
- [ ] 操作员不能通过 `userId` 查询其他用户的任务。
- [ ] 操作员不能取消其他用户不属于其业务范围的任务；明确本地农场是否允许操作员管理全部任务，并写入权限测试。
- [ ] 创建任务时校验文件存在、不是文件夹、属于当前用户或用户有管理员权限。
- [ ] `operatorId` 不信任前端传值，统一取当前 JWT 用户；管理员代操作必须明确记录。
- [ ] 任务取消、重试、重新排队必须校验当前状态和资源权限。
- [ ] WebSocket 在鉴权改造完成前仅用于开发；生产环境禁止匿名广播。

### P0.5 输入校验

- [ ] `PrinterAddDTO`、`PrinterUpdateDTO` 增加名称、IP、MAC、固件类型、网格位置校验。
- [ ] `PrintJobCreateDTO` 增加 `fileId @NotNull`、`priority` 范围校验。
- [ ] `AssignJobRequest` 增加 `jobId`、`printerId` 非空和正数校验。
- [ ] `ConfirmSafeRequest`、`StartPrintJobRequest` 增加 ID 和 action 枚举校验。
- [ ] `CreateFolderRequest` 增加名称长度、非法字符、父目录校验。
- [ ] 批量添加和批量删除增加最大数量限制，并返回每一项失败原因。
- [ ] 上传文件校验扩展名、文件大小和文件名；允许类型必须与配置实际绑定。
- [ ] 统一处理文件不存在、空文件、超限文件和 RustFS 上传失败。

### P0.6 敏感字段和生产配置

- [ ] 新增 `PrinterVO`、`PrintFileVO`、`PrintJobVO`，禁止接口直接返回 Entity。
- [ ] 打印机响应不得返回明文 `apiKey`。
- [ ] 文件响应不得返回 RustFS 内部 `rustfsKey`。
- [ ] 生产环境强制要求 `JWT_SECRET_KEY`、`ADMIN_SECRET_KEY`、MySQL、Redis、RustFS 密钥，不允许使用开发默认值。
- [ ] CORS 不再使用生产环境的 `* + credentials=true`。
- [ ] 生产环境按配置关闭 Swagger 或限制到可信局域网来源。
- [ ] 检查日志中不输出密码、JWT、API Key、数据库密码和 RustFS 密钥。

## P0：打印机协议适配基础

- [ ] 新增统一内部接口 `PrinterProtocolAdapter`。
- [ ] 定义统一方法：

```text
getStatus()
pause()
resume()
cancel()
emergencyStop()
uploadFile()
startPrint()
```

- [ ] 新增 `KlipperMoonrakerAdapter`，封装当前 `MoonrakerApiClient`。
- [ ] 将 `PrinterControlController` 中直接调用 `MoonrakerApiClient` 的代码改为调用 Service/Adapter。
- [ ] 将 `PrintJobServiceImpl` 中的上传、启动、取消设备调用改为 Adapter。
- [ ] 将 `PrinterMonitorTask` 的状态查询改为 Adapter。
- [ ] `firmwareType` 入库值统一为大写 `KLIPPER`、`RRF`，兼容旧数据 `Klipper`。
- [ ] RRF 适配器先完成接口和状态映射，再根据真实 RRF 3.7 API 实现 HTTP 调用。
- [ ] 不在 Moonraker 客户端中通过替换 URL 假装支持 RRF。

## P0：WebSocket 实时状态

- [ ] 正式地址统一为 `/ws/farm-status`；必要时短期兼容 `/ws`。
- [ ] 增加连接 Token 校验。
- [ ] 增加连接上限、心跳、异常断开和清理机制。
- [ ] 统一消息结构：

```json
{
  "type": "PRINTER_STATUS",
  "printerId": 403,
  "timestamp": 1756790000000,
  "data": {}
}
```

- [ ] 支持消息类型：`SNAPSHOT`、`PRINTER_STATUS`、`PRINTER_OFFLINE`、`JOB_STATUS`。
- [ ] 客户端连接后发送一次全量 `SNAPSHOT`。
- [ ] 打印机离线时发送 `PRINTER_OFFLINE`，不能只更新数据库而不通知前端。
- [ ] 明确是否需要按打印机订阅；第一版可先广播全部设备，但必须通过权限校验。
- [ ] 增加 WebSocket 格式、连接、断线和离线推送测试。

## P1：后端第一版功能

### P1.1 打印机管理

- [ ] 实现 `GET /api/v1/printers/{id}` 打印机详情。
  - 返回配置、统一状态、当前任务摘要和协议类型。
- [ ] 实现 `GET /api/v1/printers/{id}/history` 状态历史分页。
- [ ] 实现 `GET /api/v1/printers/{id}/statistics` 打印统计。
- [ ] 实现 `POST /api/v1/control/{id}/resume` 恢复打印。
- [ ] 实现 `POST /api/v1/control/{id}/cancel` 取消当前设备任务。
- [ ] 明确设备离线、忙碌、错误时的 HTTP 和业务错误码。
- [ ] 扫描接口支持协议识别，不再只扫描 Moonraker 7125。
- [ ] 扫描和批量添加返回每个设备的成功/失败原因。

### P1.2 文件库

- [ ] 修复现有文件分页的 `fileName`、`materialType` 筛选不生效问题。
- [ ] 实现 `GET /api/v1/print-files/tree` 目录树。
- [ ] 实现 `GET /api/v1/print-files/{id}/jobs` 文件关联任务。
- [ ] 实现 `GET /api/v1/print-files/{id}/preview` 安全预览信息。
- [ ] 统一 `folder` 布尔字段名称，避免 `isFolder` 序列化差异。
- [ ] 对已关联打印任务的文件删除给出明确策略：禁止删除或软删除。
- [ ] 下载接口继续返回预签名 URL，但增加过期时间上限和权限校验。
- [ ] 明确 RustFS 文件不存在、URL 过期和删除失败的前端提示。

### P1.3 打印任务

- [ ] 实现标准创建接口 `POST /api/v1/print-jobs`。
- [ ] 保留 `/api/v1/print-jobs/create` 作为兼容接口，并在 Swagger 标记 deprecated。
- [ ] 创建任务支持可选 `printerId`；不指定时进入 `QUEUED`。
- [ ] 实现 `POST /api/v1/print-jobs/{id}/retry`。
- [ ] 实现 `POST /api/v1/print-jobs/{id}/requeue`。
- [ ] 实现 `PUT /api/v1/print-jobs/{id}/priority`。
- [ ] 将取消逻辑从 Controller 移到 Service，统一权限、状态和设备调用。
- [ ] 安全打印流程固定为：派发 -> 安全确认 -> 启动。
- [ ] 启动时由后端记录真实操作员，不接受任意前端 `operatorId`。
- [ ] 增加任务详情中的文件摘要和打印机摘要，或明确由前端分别查询。
- [ ] 任务完成、失败、取消、暂停时通过 WebSocket 推送 `JOB_STATUS`。

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
- [ ] 适配器测试：Klipper 模拟响应、RRF 模拟响应、统一状态映射。
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

