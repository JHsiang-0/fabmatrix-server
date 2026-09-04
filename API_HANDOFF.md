# Farm 前后端接口交接与契约文档

版本：v2.0
更新时间：2026-09-03
适用范围：Farm 本地 3D 打印农场服务端与浏览器/客户端

> 本文以当前 Java 源码、`SecurityConfig` 和当前 OpenAPI 注解为准。第 4 节列出的正式 Farm API 均已有对应 Controller；开发环境兼容用路由单独列在第 4.6 节。v2 不提供后台自动派单接口，相关能力延期到 v3。

项目执行入口：[PROJECT.md](./PROJECT.md)；Kiro 需求、设计和任务清单位于 `.kiro/specs/farm-v2/`。

## 1. 产品边界

Farm 是局域网内的单农场服务端：一个服务端管理多台打印机，多个浏览器或客户端连接服务端。

当前不引入：手机号注册、邮箱验证、公开注册、多租户、联网账号中心和复杂 RBAC。

角色只有：

| 角色 | 权限 |
|---|---|
| `ADMIN` | 用户、打印机配置、文件、任务和所有设备操作 |
| `OPERATOR` | 查看打印机、文件操作、创建和控制打印任务、暂停和急停 |

所有业务接口默认要求登录，认证方式为：

```http
Authorization: Bearer <token>
```

## 2. 契约冻结规则

### 2.1 基础地址

```text
HTTP API: http://<server-host>:8080/api/v1
Swagger:  http://<server-host>:8080/swagger-ui.html
WebSocket: ws://<server-host>:8080/ws/farm-status
```

正式 WebSocket 地址为 `/ws/farm-status`。当前代码没有实现 `/ws` 别名，前端不得连接 `/ws`。

### 2.2 统一返回结构

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": 1756790000000
}
```

约定：

- `code=200`：成功。
- `data` 没有业务数据时为 `null`，不使用随机字符串表示成功。
- `timestamp`：Long，Unix epoch 毫秒。
- 新接口必须同时返回正确的 HTTP 状态码和业务码。
- P0.1 已修复业务异常码丢失问题；认证、权限、参数和核心业务异常现在返回对应 HTTP 状态与业务码。
- 客户端输入缺失的显式检查统一返回 HTTP 400、业务码 `400`：空文件上传、打印机录入缺失 IP、打印机更新缺失 ID、空文件夹名和缺失状态历史查询体均适用。数据库写入失败、设备状态冲突和任务派发失败不归入参数错误。

### 2.3 错误码

| HTTP 状态 | code | 含义 |
|---:|---:|---|
| 400 | 400 | 参数校验失败 |
| 401 | 401 | 未登录、Token 无效或过期 |
| 403 | 403 | 没有权限 |
| 404 | 404 | 资源不存在 |
| 409 | 409 | 资源冲突，例如打印机忙、名称重复 |
| 422 | 422 | 当前业务状态不允许操作 |
| 500 | 500 | 服务端异常 |
| 503 | 10001 | 打印机离线或设备不可用 |
| 409 | 10002 | 打印机忙 |
| 422 | 10003 | 打印机协议不支持当前操作 |
| 500 | 5001 | 普通 MySQL/SQL 异常 |
| 503 | 5001 | MySQL 连接不可用或查询超时 |
| 503 | 5002 | Redis 服务不可用 |
| 503 | 5003 | RustFS/对象存储服务不可用 |
| 503 | 5004 | 外部设备网络错误 |

连接池获取失败、数据库连接拒绝和查询超时由后端返回 `503/5001`；未分类的普通 `SQLException` 保留 `500/5001`。Redis 连接失败和 `StorageException` 分别返回 `503/5002`、`503/5003`。

错误响应示例：

```json
{
  "code": 10001,
  "message": "打印机当前离线",
  "data": null,
  "timestamp": 1756790000000
}
```

### 2.4 分页

请求统一使用：

```json
{
  "pageNum": 1,
  "pageSize": 20
}
```

当前四类分页 Controller 已统一返回：

```json
{
  "records": [],
  "total": 100,
  "pageNum": 1,
  "pageSize": 20,
  "pages": 5
}
```

Service 层仍使用 MyBatis-Plus `Page/IPage`，Controller 已通过 `PageResult` 转换为本文结构。分页参数要求 `pageNum>=1`、`1<=pageSize<=100`，非法参数返回 HTTP 400、`code=400`。分页查询请求体缺失时，各业务 Service 统一返回 HTTP 400、`code=400`，不会产生空指针错误。任务分页的 `startTime`、`endTime` 均为可选的本地时间；当 `startTime` 晚于 `endTime` 时返回 HTTP 400、`code=400`。

所有 JSON 请求体缺失或为 `null` 时，Controller 统一返回 HTTP 400、`code=400`；批量操作传入空列表同样返回 HTTP 400，不会以服务器内部错误响应。

任务状态已统一为 `QUEUED`、`ASSIGNED`、`UPLOADING`、`READY`、`PRINTING`、`PAUSED`、`RECONCILING`、`COMPLETED`、`FAILED`、`CANCELLED`。新建任务进入 `QUEUED`；v2 后台调度器默认关闭，用户手动派发或 v3 未来启用的调度流程必须先进入 `ASSIGNED`，设备调用成功后才进入 `PRINTING`。手动派发由任务 Service 重新校验任务为 `QUEUED`、打印机为 `IDLE` 且未绑定其他任务，并在任务和打印机均保存成功后发布 `JOB_STATUS`；任一保存失败不会发布成功事件。非法状态流转返回 HTTP 422、`code=422`。历史数据库中的 `PENDING`、`MANUAL` 会兼容转换为 `QUEUED`，`CANCELED` 会转换为 `CANCELLED`。设备在任务执行期间异常回到 `idle/standby/ready` 且无法证明完成时，任务进入 `RECONCILING`，保留设备绑定并等待人工核对。

## 3. 认证接口

### 3.1 登录

```http
POST /api/v1/auth/login
```

请求：

```json
{
  "username": "admin",
  "password": "<管理员当前密码>"
}
```

返回：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJ...",
    "expiresIn": 604800,
    "userId": 1,
    "username": "admin",
    "role": "ADMIN",
    "email": null,
    "phone": null
  },
  "timestamp": 1756790000000
}
```

`expiresIn` 单位为秒。当前没有 refresh token、logout 黑名单或服务端会话表。第一版退出登录由客户端删除本地 Token 并清空用户状态实现，不调用不存在的 `/auth/logout`；Token 到期后必须重新登录。

登录失败契约：账号不存在、密码错误、账号已锁定统一返回 HTTP 401、业务码 `401`；账号被管理员禁用返回 HTTP 403、业务码 `403`。失败次数由 Redis 记录，连续失败 5 次后锁定 15 分钟；Redis 不可用时返回 HTTP 503、业务码 `5002`。Bearer Token 无效或过期返回 HTTP 401、业务码 `401`，禁用用户携带既有 Token 访问业务接口返回 HTTP 403、业务码 `403`。

### 3.1.1 Local Edition 首次管理员初始化

Local Edition 全新数据目录没有预置管理员账号。前端首次打开时先调用：

| 方法 | 地址 | 权限 | 请求 | 返回 |
|---|---|---|---|---|
| GET | `/auth/setup/status` | 免认证 | 无 | `Result<FirstAdminSetupStatusDTO>` |
| POST | `/auth/setup/admin` | 免认证（仅首次） | 用户注册 DTO | `Result<LoginResultDTO>` |

`FirstAdminSetupStatusDTO` 字段：`initialized` 表示数据库是否已有用户，`setupAvailable` 表示当前是否允许创建首个管理员。Local Edition 开启该能力，其他 Profile 默认关闭。

创建请求复用 `UserRegisterDTO`：用户名 3-20 位，只能使用字母、数字、下划线；密码 6-20 位且必须包含大小写字母和数字；`confirmPassword` 必须一致。服务端固定将首个用户创建为 `ADMIN`，密码只保存 BCrypt 哈希，不提供固定默认密码。

创建成功后直接返回登录结果（包含 `data.token`、`userId`、`username`、`role=ADMIN`），前端可以直接保存 Token。创建成功后再次调用初始化接口返回 HTTP 409、业务码 `409`；如果初始化能力未开启返回 HTTP 404、业务码 `404`。服务层会在数据库写入前再次检查用户数量，并在单进程内串行化初始化请求。

### 3.2 用户管理

| 方法 | 地址 | 权限 | 请求 | 返回 |
|---|---|---|---|---|
| POST | `/auth/register` | ADMIN | 用户注册 DTO | `Result<Long>` |
| POST | `/auth/admin/users` | ADMIN | 用户注册 DTO | `Result<Long>` |
| GET | `/auth/admin/users` | ADMIN | `pageNum,pageSize,username,role,email` | `PageResult<UserVO>`，不包含密码 |
| PUT | `/auth/admin/users/{userId}` | ADMIN | 用户更新 DTO | `Result<null>` |
| POST | `/auth/admin/users/{userId}/disable` | ADMIN | Path ID | `Result<null>` |
| POST | `/auth/admin/users/{userId}/enable` | ADMIN | Path ID | `Result<null>` |
| POST | `/auth/admin/migrate-passwords` | ADMIN | Header：`X-Admin-Secret` | `Result<PasswordMigrateResultDTO>` |
| GET | `/auth/admin/password-status` | ADMIN | Header：`X-Admin-Secret` | `Result<PasswordStatusResultDTO>` |
| GET | `/auth/check-username` | ADMIN | Query：`username`，不能为空或纯空格 | `Result<Boolean>` |
| GET | `/auth/check-email` | ADMIN | Query：`email`，不能为空或纯空格 | `Result<Boolean>` |
| GET | `/auth/me` | ADMIN/OPERATOR | 无 | `Result<UserVO>` |
| GET | `/auth/{userId}/profile` | 本人 | 无 | `Result<UserVO>` |
| PUT | `/auth/{userId}/profile` | 本人 | 邮箱、手机号 | `Result<null>` |
| POST | `/auth/{userId}/change-password` | 本人 | 旧密码、新密码、确认密码 | `Result<null>` |

密码规则由后端强制校验：6-20 位，必须包含大写字母、小写字母和数字。`CUSTOMER` 不再使用。

管理员密码迁移和状态检查接口只接受 `X-Admin-Secret` 请求头，不接受 URL 查询参数，避免管理员密钥进入浏览器、代理或访问日志。

`GET /auth/me` 从 Bearer JWT 的当前用户 ID 读取用户资料，不需要也不接受路径参数；前端登录成功后可直接调用该接口初始化用户状态。用户资料和管理员用户分页统一返回 `UserVO`，字段只有 `id,username,role,email,phone,createdAt,updatedAt`，不包含 `passwordHash`。

## 4. 当前正式业务接口（与 Controller 一一对应）

以下接口以当前 Controller 为准；除 Local Edition 的文件流入口 `GET /print-files/storage` 外，均使用 `Result<T>` 包装。

### 4.1 打印机

| 方法 | 地址 | 权限 | 参数 | 返回 |
|---|---|---|---|---|
| GET | `/printers/page` | ADMIN/OPERATOR | Query：`pageNum,pageSize,name,status` | `PageResult<PrinterVO>`，不含 apiKey |
| GET | `/printers/{id}` | ADMIN/OPERATOR | Path：打印机 ID | `PrinterDetailVO`：安全配置、缓存实时状态和当前任务摘要 |
| POST | `/printers/add` | ADMIN | 打印机配置 | `Result<null>` |
| PUT | `/printers/update` | ADMIN | 包含 `id` 的打印机配置 | `Result<null>` |
| DELETE | `/printers/delete/{id}` | ADMIN | Path ID | `Result<null>` |
| GET | `/printers/scan` | ADMIN | Query：`subnet`，三段 IPv4 网段前缀，例如 `192.168.1` | Klipper/RRF 协议识别扫描结果数组；格式错误返回 `400/400` |
| POST | `/printers/batch-add` | ADMIN | 扫描结果数组 | 批量新增/更新统计及逐项结果 |
| GET | `/printers/by-mac/{macAddress}` | ADMIN/OPERATOR | Path MAC | `PrinterVO` 或 null |
| GET | `/printers/by-ip/{ipAddress}` | ADMIN/OPERATOR | Path IP | `PrinterVO` 或 null |
| PUT | `/printers/positions` | ADMIN | 位置更新数组 | `Result<null>` |
| GET | `/printers/unallocated` | ADMIN/OPERATOR | Query：`keyword` | `PrinterVO[]` |

新增、重新录入或批量扫描入库的打印机，在下一次协议状态探测完成前返回 `status=UNKNOWN`；`ONLINE` 不属于打印机状态枚举，也不会作为业务状态写入。普通配置编辑不重置设备当前状态。

### 4.2 文件

| 方法 | 地址 | 权限 | 参数 | 返回 |
|---|---|---|---|---|
| POST | `/print-files/upload` | ADMIN/OPERATOR | Multipart：`file`、`parentId?` | `PrintFileVO`，不含 `rustfsKey`/`safeName`/`fileUrl` |
| POST | `/print-files/batch-upload` | ADMIN/OPERATOR | Multipart：可重复字段 `files`、`parentId?`，最多100个、总大小默认1GB | `BatchUploadResult`，逐项返回 fileId/status/errorCode/retryable；不自动创建设备任务 |
| POST | `/print-files/page` | ADMIN/OPERATOR | JSON：分页、`fileName`、`materialType`、`parentId?` | `PageResult<PrintFileVO>` |
| GET | `/print-files/tree` | ADMIN/OPERATOR | 无 | `FileNodeVO[]` |
| GET | `/print-files/{id}/jobs` | ADMIN/OPERATOR | Query：`pageNum,pageSize` | `PageResult<PrintJobVO>` |
| GET | `/print-files/{id}/preview` | ADMIN/OPERATOR | Path ID | `PrintFilePreviewVO` |
| GET | `/print-files/{id}/thumbnail` | ADMIN/OPERATOR | Query：`expires`，单位分钟 | 短期预签名缩略图 URL 或 null |
| GET | `/print-files/{id}/download` | ADMIN/OPERATOR | Query：`expires` | 预签名 URL 字符串 |
| GET | `/print-files/storage` | ADMIN/OPERATOR | Query：`key` | Local Edition 受保护文件流（`text/plain`） |
| DELETE | `/print-files/{id}` | ADMIN/OPERATOR | Path ID | `Result<null>` |
| DELETE | `/print-files/batch` | ADMIN/OPERATOR | `{"ids":[1,2]}`，最多100个 | `Result<BatchDeleteResult>`，包含每个 ID 的成功/失败原因 |
| GET | `/print-files/folder/content` | ADMIN/OPERATOR | Query：`parentId?` | `PrintFileVO[]` |
| POST | `/print-files/folder/create` | ADMIN/OPERATOR | `parentId,folderName` | `PrintFileVO` |

`POST /print-files/page` 的筛选约定：`fileName` 对 `original_name` 做包含匹配，服务端会去除首尾空格；`materialType` 对 `material_type` 做精确匹配，服务端会去除首尾空格并按大写规范化（例如 ` pla ` 等价于 `PLA`）。操作员始终只能查询本人文件，管理员可通过 `userId` 查询指定用户，不传则查询全部用户。

文件库目录约定：`parentId` 省略或为 `null` 表示根目录；上传和分页查询均支持该字段，指定目录必须属于当前用户（管理员可访问全部目录）。前端不要发送 `keyword`，文件名搜索字段固定为 `fileName`。

`GET /print-files/folder/content` 返回指定目录的直接子节点，排序固定为目录优先、同级创建时间倒序；根目录通过省略 `parentId` 查询。

### 4.3 打印任务

| 方法 | 地址 | 权限 | 参数 | 返回 |
|---|---|---|---|---|
| GET | `/print-jobs/queue` | ADMIN/OPERATOR | 无 | `PrintJobVO[]` |
| POST | `/print-jobs/page` | ADMIN/OPERATOR | JSON：分页、状态、打印机、时间 | `PageResult<PrintJobVO>` |
| GET | `/print-jobs/{id}` | ADMIN/OPERATOR | Path ID | `PrintJobVO` |
| POST | `/print-jobs` | ADMIN/OPERATOR | `fileId,priority,printerId?,idempotencyKey?` | 新任务 ID；同一用户同一幂等键重复请求返回原任务 |
| POST | `/print-jobs/create` | ADMIN/OPERATOR | `fileId,priority,printerId?,idempotencyKey?` | 新任务 ID（兼容，deprecated） |
| DELETE | `/print-jobs/{id}` | ADMIN/OPERATOR | Path ID | 取消任务（Service 统一校验、设备控制和解绑） |
| POST | `/print-jobs/{id}/retry` | ADMIN/OPERATOR | Path ID | 失败任务重新入队 |
| POST | `/print-jobs/{id}/requeue` | ADMIN/OPERATOR | Path ID | 已派发任务重新入队 |
| PUT | `/print-jobs/{id}/priority` | ADMIN/OPERATOR | JSON：`priority` | 更新排队优先级 |
| POST | `/print-jobs/{jobId}/assign` | ADMIN/OPERATOR | Query：`printerId` | 分配并启动 |
| POST | `/print-jobs/safe/assign` | ADMIN/OPERATOR | `jobId,printerId` | 安全派发 |
| POST | `/print-jobs/safe/confirm` | ADMIN/OPERATOR | `printerId,operatorId?` | 安全确认 |
| POST | `/print-jobs/safe/start` | ADMIN/OPERATOR | `jobId,operatorId?,action?` | 启动或仅上传 |
| POST | `/print-jobs/batch/preview` | ADMIN/OPERATOR | `fileIds,printerIds,strategy,action` | `DispatchPlanPreviewVO`；预览无副作用 |
| POST | `/print-jobs/batch/confirm` | ADMIN/OPERATOR | `planId,version,itemIds,confirmationToken` | `BatchDispatchConfirmVO`；逐项创建任务并返回结果 |

### 4.4 设备控制

| 方法 | 地址 | 权限 | 参数 | 返回 |
|---|---|---|---|---|
| POST | `/control/{id}/pause` | ADMIN/OPERATOR | Path 打印机 ID | `Result<null>` |
| POST | `/control/{id}/resume` | ADMIN/OPERATOR | Path 打印机 ID | `Result<null>` |
| POST | `/control/{id}/cancel` | ADMIN/OPERATOR | Path 打印机 ID | `Result<null>` |
| POST | `/control/{id}/emergency-stop` | ADMIN/OPERATOR | Path 打印机 ID | `Result<null>` |

### 4.5 当前输入校验约定

- 打印机名称最多100个字符；IP 必须为 IPv4；MAC 支持冒号或连字符格式；固件类型只能是 `KLIPPER` 或 `RRF`（大小写兼容）；网格范围为行 `1-4`、列 `1-12`。
- 创建任务的 `fileId` 必须为正数，`priority` 范围为 `0-100`。
- 创建任务的 `idempotencyKey` 可选，最大 100 个字符；同一用户同一键必须对应相同的文件、打印机和优先级，否则返回 HTTP 409、业务码 `409`。
- 用户名和邮箱可用性检查的 Query 参数不能为空或只包含空格，否则返回 HTTP 400、业务码 `400`。
- 派发、确认安全、启动任务的 ID 必须为正数；`action` 只能是 `START_PRINT` 或 `UPLOAD_ONLY`。`operatorId` 仍兼容接收，但后端忽略其值并使用 JWT 当前用户。
- 文件夹名称最多100个字符，不允许 `/`、`\\`、控制字符及 `:*?\"<>|`；`parentId` 必须为正数或省略表示根目录。

- 批量添加打印机、批量删除文件、批量更新位置单次最多100项。批量删除返回 `items`，每项包含 `id`、`success`、`reason`；批量添加返回 `items`，每项包含 `index`、地址、成功标志和原因。
- 批量文件上传接口为 `POST /api/v1/print-files/batch-upload`，使用 `multipart/form-data` 的重复字段 `files`。默认最多100个文件、总大小1GB；开发环境总大小上限250MB。接口逐项返回 `index`、`fileId`、`fileName`、`status`、`errorCode`、`message`、`retryable`，单项失败不回滚其他已成功文件；空列表、数量或总大小超限返回 HTTP 400。
- 文件上传扩展名从 `farm.file.allowed-types` 读取，默认允许 `gcode,g,3mf,stl`；开发环境上限200MB，生产环境上限1GB。空文件、非法文件名、超限和不支持类型统一返回 HTTP 400；RustFS 失败返回 HTTP 503、业务码 `5003`。
- 文件上传先写入 RustFS、再保存文件记录；若数据库保存失败，后端会尝试补偿删除已上传的主文件和缩略图对象。补偿删除失败只记录日志，接口仍返回原始保存错误。
- 删除文件时先清理关联缩略图，再删除主文件对象；任一对象存储删除失败都会保留数据库记录并返回 `503/5003`，避免前端误判删除成功。

### 4.6 开发环境 Moonraker 兼容接口

以下路由由 [MoonrakerMockController](src/main/java/com/example/farm/controller/MoonrakerMockController.java) 提供，只有 `dev`、`test` Profile 加载，用于兼容 OrcaSlicer 等工具，不属于 `/api/v1` Farm 业务 API，也不使用 `Result<T>` 返回格式。配置了 `farm.moonraker-api-key` 时，所有路由都要求 `X-Api-Key` 请求头；未配置时仅适合本地开发。

| 方法 | 地址 | 请求参数 | 返回格式 |
|---|---|---|---|
| GET | `/server/info` | Header：`X-Api-Key?` | Moonraker `{"result":{...}}` |
| GET | `/printer/info` | Header：`X-Api-Key?` | Moonraker `{"result":{...}}` |
| GET | `/machine/update/status` | Header：`X-Api-Key?` | Moonraker `{"result":{...}}` |
| POST | `/server/files/upload` | Multipart：`file`；Query：`print?`；Header：`X-Api-Key?` | Moonraker `{"result":{"item":{...}}}` |
| GET | `/server/files` | Header：`X-Api-Key?` | Moonraker `{"result":[]}` |
| DELETE | `/server/files/{filename:.+}` | Path：`filename`；Header：`X-Api-Key?` | Moonraker `{"result":{"deleted":"..."}}` |

该模拟控制器返回固定或简化的兼容数据，不代表真实 Klipper 或 RRF 设备能力；生产 Profile 不加载。

## 5. 已完成接口补充契约

### 5.1 打印机详情和控制

`GET /api/v1/printers/{id}` 返回：

```json
{
  "printer": { "id": 403, "name": "Printer_C0DA", "firmwareType": "KLIPPER", "status": "IDLE" },
  "realtimeStatus": null,
  "currentJob": null
}
```

`printer` 使用 `PrinterVO`，不含 `apiKey`；`realtimeStatus` 使用当前状态缓存对象，未命中时为 `null`；`currentJob` 使用 `PrintJobVO`，没有绑定任务时为 `null`。打印机是本地农场共享资源，ADMIN/OPERATOR 均可查询；不存在的打印机返回 404 业务错误。

| 方法 | 目标地址 | 权限 | 请求 | 返回 | 状态 |
|---|---|---|---|---|---|
| GET | `/printers/{id}` | ADMIN/OPERATOR | Path ID | `PrinterDetailVO` | 已完成 |
| GET | `/printers/{id}/history` | ADMIN/OPERATOR | `from,to,pageNum,pageSize` | `PageResult<PrinterStatusHistoryVO>` | 已完成 |
| GET | `/printers/{id}/statistics` | ADMIN/OPERATOR | `from,to` | `PrinterStatisticsVO` | 已完成 |
| POST | `/control/{id}/resume` | ADMIN/OPERATOR | Path ID | `Result<null>` | 已完成 |
| POST | `/control/{id}/cancel` | ADMIN/OPERATOR | Path ID | `Result<null>` | 已完成 |

设备控制接口必须经过统一协议适配器，不允许 Controller 直接调用 Moonraker 客户端。

状态历史接口契约：

```http
GET /api/v1/printers/{id}/history?pageNum=1&pageSize=20&from=2026-09-01T00:00:00&to=2026-09-02T23:59:59
Authorization: Bearer <token>
```

返回 `Result<PageResult<PrinterStatusHistoryVO>>`。`records` 按 `recordedAt` 倒序，字段包括
`id`、`printerId`、`status`、`rawState`、`systemMessage`、`filename`、`progress`、温度目标/当前值、
耗材/时长数据和 `recordedAt`。时间参数使用不带时区的 ISO-8601 本地时间；`pageNum` 从1开始，
`pageSize` 范围为1-100；`from` 晚于 `to` 返回 HTTP 400。不存在打印机返回 HTTP 404。

状态历史和统计的路径 `id` 必须为正数；`from`/`to` 同时提供时，`from` 不得晚于 `to`。违反这些参数约束返回 HTTP 400、业务码 `400`；打印机不存在仍返回 HTTP 404。

状态历史的存储边界已经冻结：Redis List 仍用于最近高频状态（最多2880条、24小时过期），
MySQL 表 `farm_printer_status_history` 保存首次样本、状态变化样本和每分钟采样样本，支持服务重启后分页查询。
新增数据库卷会自动执行 `06-add-printer-status-history.sql`；已有 Docker 数据卷不会自动执行，升级前备份后手工执行该脚本。

状态历史写入属于监控旁路：MySQL 插入失败不会阻塞当前设备状态监控，但只有 Mapper 实际影响行数大于 0 时才算样本已持久化；失败样本不会更新时间游标，后续采样会继续尝试写入。

打印机状态写入只有在数据库更新实际影响目标记录后才视为成功；若数据库更新影响 0 行，监控任务不会据此更新本地状态或发布成功状态事件。

统计接口契约：`GET /api/v1/printers/{id}/statistics?from=...&to=...`。时间范围按任务
`createdAt` 筛选，省略时间表示查询全部任务；返回 `printerId`、`from`、`to`、`totalJobs`、
`completedJobs`、`failedJobs`、`cancelledJobs`、`activeJobs`、`successRate`、
`totalPrintSeconds` 和 `averagePrintSeconds`。成功率为“已完成 /（已完成 + 失败）”百分比，
取消任务不计入分母；时长单位为秒，无完整开始/完成时间的任务不计入时长。不存在打印机返回 HTTP 404，
`from` 晚于 `to` 返回 HTTP 400。

恢复和取消当前设备任务的约定：`POST /api/v1/control/{id}/resume` 仅允许打印机有绑定任务且任务状态为
`PAUSED` 时调用；成功后设备执行恢复、任务变为 `PRINTING`，并推送 `JOB_STATUS`。没有绑定任务或状态不允许时返回
HTTP 422，设备离线返回 `code=10001`。恢复时任务和打印机状态必须都保存成功后才推送 `JOB_STATUS`，打印机状态保存失败不会返回成功。
`POST /api/v1/control/{id}/cancel` 要求设备有绑定任务，复用任务取消服务完成
归属校验、协议调用、任务状态变为 `CANCELLED` 和打印机解绑；没有绑定任务返回 HTTP 422。

### 5.2 任务

| 方法 | 目标地址 | 权限 | 请求 | 返回 | 状态 |
|---|---|---|---|---|---|
| POST | `/print-jobs` | ADMIN/OPERATOR | `fileId,priority,printerId?` | 新任务 ID | 已完成 |
| POST | `/print-jobs/{id}/retry` | ADMIN/OPERATOR | Path ID | `Result<null>` | 已完成 |
| POST | `/print-jobs/{id}/requeue` | ADMIN/OPERATOR | Path ID | `Result<null>` | 已完成 |
| PUT | `/print-jobs/{id}/priority` | ADMIN/OPERATOR | JSON：`priority`，范围 `0-100` | `Result<null>` | 已完成 |

现有 `/print-jobs/create` 保留为兼容地址并标记 deprecated，前端新代码统一使用 `POST /print-jobs`。两条地址调用同一 Service 逻辑；不传 `printerId` 创建 `QUEUED`，传入后按 T6.2 规则进入 `ASSIGNED`。

T6.2 的 `printerId` 规则：不传时任务为 `QUEUED` 且不绑定设备；传入时先创建任务，再复用安全派发逻辑校验设备存在、为 `IDLE` 且 `currentJobId=null`，成功后任务为 `ASSIGNED`、打印机绑定任务且 `isSafeToPrint=false`，不会直接开始打印。任务记录插入影响 0 行时直接返回业务错误，不返回任务 ID；设备忙碌、设备不存在、已有任务绑定或派发状态校验失败时整体创建事务回滚；手动接口对已有绑定返回 `409`。

T6.3 重试规则：`POST /print-jobs/{id}/retry` 仅允许当前用户可见且状态为 `FAILED` 的任务；成功后保留 `fileId`、`userId` 和 `priority`，清除 `printerId`、`operatorId`、`startedAt`、`completedAt`、`errorReason`，进度重置为 `0`，状态变为 `QUEUED`。由于队列任务没有 `printerId`，不构造无设备 ID 的 `JOB_STATUS`；前端以任务列表/队列数据为准。非失败状态返回 HTTP 422，不调用打印机设备；任务不存在或无权访问统一返回 HTTP 404。

T6.4 重新排队规则：`POST /print-jobs/{id}/requeue` 仅允许 `ASSIGNED` 或 `READY` 任务；成功后解除打印机绑定、清除运行字段、进度归零，状态变为 `QUEUED`。由于解除绑定后没有 `printerId`，不构造无设备 ID 的 `JOB_STATUS`；前端以任务列表/队列数据为准。关联设备存在时清除其 `currentJobId` 和安全确认，`PREPARING` 设备恢复为 `IDLE`；不调用设备协议。`PRINTING`、`PAUSED`、`FAILED`、`COMPLETED`、`CANCELLED` 均返回 HTTP 422，任务或设备不存在/无权访问返回 HTTP 404。

T6.5 优先级规则：`PUT /print-jobs/{id}/priority` 接收 `{ "priority": 0 }`，范围为 `0-100`，仅允许当前用户可见且状态为 `QUEUED` 的任务修改。成功后只更新优先级；已派发、打印中或已结束任务返回 HTTP 422，任务不存在或无权访问返回 HTTP 404。该操作不调用设备，也不发送无打印机目标的 `JOB_STATUS`，调度器下一轮按新优先级取队列。

T6.6 取消规则已统一收敛到 `PrintJobService.cancelJob`：Controller 不直接访问设备协议；Service 负责当前用户归属、状态转换、打印机适配器取消、设备解绑、数据库持久化和 `JOB_STATUS` 事件。队列任务直接取消，已绑定任务先成功调用设备取消后再解绑；设备异常时不伪造取消成功。

任务服务的手动派发、启动、取消、重新排队和现场安全确认，均必须检查任务/打印机记录的实际更新结果；任一必要记录保存失败返回业务错误并回滚，不发布成功的 `JOB_STATUS`。设备监控驱动的完成、失败和取消属于独立的异步同步路径，需遵循同样的双记录保存约束。

设备监控收到完成、失败或设备取消状态时，必须先成功持久化打印机解绑/安全标记，再持久化任务终态；打印机解绑保存失败时本轮不结束任务、不发布 `JOB_STATUS`，保留绑定状态等待下一轮巡检重试。

用户创建、修改密码、资料更新和密码迁移接口以数据库实际写入结果为准；写入影响 0 行时返回失败，不向前端返回成功，避免出现账号/密码状态未改变但接口成功的情况。

打印机新增、重新录入、编辑和删除接口同样以数据库实际写入结果为准；插入/更新/删除失败会返回业务错误并触发事务回滚，不会刷新缓存后伪装成成功。

文件夹创建和单文件删除以数据库实际写入结果为准；目录记录插入或文件记录删除影响 0 行时返回业务错误。对象存储删除发生在数据库删除之前，若数据库删除失败，服务端不会返回成功，残留对象需按运维清理流程处理。

T6.7 任务摘要采用前端组合查询方案：`PrintJobVO` 保留 `fileId` 和 `printerId`，不在任务分页中嵌套重复对象；前端需要文件摘要时调用 `/print-files/{fileId}/preview`，需要打印机摘要时调用 `/printers/{printerId}`。`printerId=null` 的排队任务不发起打印机查询，文件/打印机详情接口各自执行资源权限校验。

T6.8 当前已完成任务 Service 的状态/归属/设备调用测试、任务路由认证测试，以及持久化成功后绑定设备任务的 `JOB_STATUS` 事件测试。由于队列任务没有设备 ID，重试、重新排队和优先级修改不发送 `JOB_STATUS`；真实 MySQL/Redis/RustFS/打印机的端到端链路仍需在现场环境验收。

T7.1 已完成：`GET /auth/me` 要求登录，从 JWT 当前用户读取资料并返回脱敏 `UserVO`；未携带或无效 Token 返回 HTTP 401、业务码 `401`。原 `/auth/{userId}/profile` 保留用于兼容，前端新代码优先使用 `/auth/me`。

T7.2 已完成：用户资料和管理员分页不再直接返回持久化实体 `User`，统一映射为 `UserVO`，从类型层面排除 `passwordHash`；密码哈希仍只在 Service 内部用于登录和密码迁移。

T7.3 已完成：管理员创建操作员、更新用户角色、禁用和启用接口均有 Controller 集成测试；OPERATOR 调用上述 ADMIN-only 路由统一返回 HTTP 403、业务码 `403`。

T7.4 已完成：登录失败、Redis 锁定、禁用用户和无效 Token 的 HTTP/业务码已统一；认证失败为 401，禁用用户为 403，Redis 故障仍映射为 503/5002。失败原因只返回稳定业务提示，不返回密码或 Token 内容。

T7.5 已完成：第一版不实现服务端 logout。原因是当前产品为本地单服务，未引入 Token 黑名单和会话存储；前端退出时删除 Token、用户信息和 WebSocket 连接即可。若未来需要主动吊销 Token，再单独冻结黑名单/Token 版本契约。

T9.1 已完成：核心认证路由集成测试覆盖打印机、文件、任务和用户管理入口的匿名 401，以及打印机和用户管理 ADMIN-only 操作的 OPERATOR 403；管理员用户管理成功委托路径也已覆盖。真实数据库成功响应仍需容器环境联调。

T9.3 已完成：登录失败计数使用 `farm:login:fail:{username}`、首次写入 15 分钟过期；5 次及以上视为锁定；禁用标记使用 `farm:user:disabled:{userId}`；打印机状态缓存 TTL 为 10 秒，状态锁 TTL 为 5 秒。Redis 单元测试覆盖 key、TTL、锁未获取时不写库和禁用标记生命周期；真实 Redis 容器联调仍待现场环境。

T9.4 已完成：RustFS 客户端上传、预签名 URL、删除失败均统一转换为 `StorageException`；文件 Service 额外执行资源归属、过期时间上限和删除前任务引用校验。`RustFsClientTest` 与 `PrintFileOwnershipTest` 使用 mock 覆盖成功委托、异常转换和安全边界，不连接真实 RustFS。

T9.5 已完成：Klipper/Moonraker 与 RRF 均通过统一 Adapter Factory 选择；适配器测试覆盖协议状态映射、暂停/恢复/取消/急停、上传和不支持能力，RRF HTTP 客户端测试覆盖会话、状态、G-code 与上传请求。真实目标设备的响应级联调已完成，生产 G-code 的运动、加热和其他物理副作用仍需现场验收。

T9.8 已完成后端基础部分：`GET /actuator/health` 为免认证探活端点且不返回依赖详情，`health/info` 为基础暴露范围；生产环境仍由 `ProductionSafetyValidator` 收紧密钥、CORS 和 Swagger/OpenAPI，且已有配置回归测试。启动顺序、备份、迁移和无真实打印机时关闭任务的要求见 `OPERATIONS.md`。RustFS 和打印机真实连通性仍需现场检查。

T9.9 已完成：`PRINTER_OFFLINE` 使用稳定的 `printerId/status/reason` 数据，连续离线由监控逻辑抑制重复事件；设备恢复时重新发布 `PRINTER_STATUS`。失败任务通过 `JOB_STATUS` 携带 `jobId/status/progress/errorReason`。服务端每 30 秒发送 WebSocket 协议级 Ping，失败连接自动清理，不新增业务消息类型。真实前端 `/home/codex/workspace/farm-ui` 已实现断线重连、指数退避、离线/失败告警展示和对应自动化测试；浏览器级真实后端端到端仍待完成。

Service 层测试已覆盖任务状态转换、重试/重新排队/优先级更新、文件和任务资源归属、文件删除保护、打印机控制前置校验、协议异常和监控离线处理；Mapper 的真实 MySQL 查询分页、完整设备链路和端到端流程仍需现场环境。

### 5.3 文件

| 方法 | 目标地址 | 权限 | 请求 | 返回 | 状态 |
|---|---|---|---|---|---|
| GET | `/print-files/tree` | ADMIN/OPERATOR | 无 | `FileNodeVO[]` | 已完成 |
| GET | `/print-files/{id}/jobs` | ADMIN/OPERATOR | Query：`pageNum,pageSize` | `PageResult<PrintJobVO>` | 已完成 |
| GET | `/print-files/{id}/preview` | ADMIN/OPERATOR | Path ID | `PrintFilePreviewVO` | 已完成 |

文件接口按当前登录用户隔离资源；`OPERATOR` 只能访问本人文件，`ADMIN` 可查看和管理全部文件。管理员分页查询可通过 `userId` 筛选指定用户，不传时查询全部。

`GET /print-files/tree` 当前冻结为返回完整树，不分页、不接受必填参数；若以后传入 `parentId`，只作为从指定目录开始的兼容扩展。`FileNodeVO` 字段为：`id`、`parentId`、`folder`、`name`、`fileSize`、`materialType`、`createdAt`、`children`。目录和文件均返回 `children` 数组，文件节点数组为空；`name` 为目录名或文件原始名。节点按目录优先、同级创建时间倒序排列。操作员只获得本人节点，管理员获得全部节点；孤立节点按根节点返回，避免前端丢失数据。

`GET /print-files/{id}/jobs` 先校验文件对当前用户可见，再按 `file_id` 分页查询关联任务；操作员只能看到自己发起的任务，管理员可看到该文件的全部任务。`pageNum` 范围为 `1-Long.MAX_VALUE`，`pageSize` 范围为 `1-100`；文件不存在或无权访问均返回 HTTP 404，成功返回统一 `PageResult<PrintJobVO>`。

`GET /print-files/{id}/preview` 先校验文件归属，只返回已入库的安全预览元数据：`id`、`originalName`、`fileSize`、`materialType`、`estTime`、`nozzleSize`、耗材用量、温度和层高字段。该接口不读取或返回 G-code 原文、缩略图直连地址、`safeName`、`rustfsKey`、`fileUrl` 或下载 URL；文件不存在、目录资源或无权访问均返回 HTTP 404/422 的明确业务错误。

`GET /print-files/{id}/thumbnail` 用于按需加载缩略图。接口先校验文件归属，再把历史保存的 RustFS 对象地址转换为短期预签名 URL；`expires` 与下载接口共用默认值 60 分钟和服务端上限（默认 120 分钟），没有缩略图时成功返回 `data=null`。缩略图直连地址只保留在后端实体内部，不通过文件 VO、预览 VO 或日志输出。

文件删除策略固定为“禁止删除已关联任务的文件”：只要 `farm_print_job.file_id` 存在关联记录（包括已取消、失败和已完成任务），单个删除返回 HTTP 409、业务码 `409`，批量删除在对应 item 中返回失败原因，不影响其他可删除项。目录资源不能通过文件删除接口删除，返回 HTTP 422；对象存储删除失败返回 HTTP 503、业务码 `5003`，数据库记录不会先行删除。

下载接口的 `expires` 单位为分钟，未传或小于等于 0 时使用 60 分钟；服务端通过 `farm.file.presigned-url-max-minutes` 强制上限，默认 120 分钟，超过上限按 120 分钟签发。接口先校验当前用户对文件的访问权限，再调用 RustFS 生成 URL；RustFS 生成失败返回 HTTP 503/业务码 `5003`。预签名 URL 过期后由对象存储返回直连错误，前端应重新调用下载接口获取新 URL。

文件存储错误的前端处理约定：后端生成下载/缩略图 URL 或删除对象失败时返回 HTTP `503`、业务码 `5003`，前端提示“文件存储服务暂不可用，请稍后重试”，不要把操作显示为成功；预签名 URL 在浏览器直连时过期，前端应丢弃旧 URL、重新调用对应下载/缩略图接口后再重试一次；文件对象不存在或记录与对象不一致时同样按 `5003` 提示“文件已不可用，请联系管理员清理”；已关联任务删除返回 `409`，提示“文件已关联打印任务，无法删除”，不自动重试。

## 6. 数据模型

### 6.1 PrinterVO / PrinterDetailVO

```json
{
  "id": 403,
  "name": "Printer_C0DA",
  "ipAddress": "192.168.1.80",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "firmwareType": "RRF",
  "status": "IDLE",
  "isSafeToPrint": false,
  "currentJobId": null,
  "currentMaterial": "PLA",
  "nozzleSize": 0.4,
  "machineNumber": "A-01",
  "gridRow": 1,
  "gridCol": 1,
  "createdAt": "2026-09-02T17:00:00",
  "updatedAt": "2026-09-02T17:00:00"
}
```

协议类型只允许：

```text
KLIPPER
RRF
```

`apiKey` 只允许出现在新增/修改请求中，任何响应 DTO 都不得返回明文 API Key。编辑打印机时若 `apiKey` 未传或为空白，表示保留原有凭据；当前版本不通过普通编辑接口清空设备凭据，避免前端因脱敏字段缺失而误删 RRF/Klipper 认证信息。

打印机状态统一为：

```text
OFFLINE
IDLE
PREPARING
PRINTING
PAUSED
ERROR
UNKNOWN
```

### 6.2 PrintJobVO

```json
{
  "id": 1001,
  "fileId": 20,
  "printerId": 403,
  "userId": 1,
  "operatorId": 2,
  "priority": 10,
  "status": "PRINTING",
  "progress": 35.5,
  "startedAt": "2026-09-02T17:10:00",
  "completedAt": null,
  "errorReason": null,
  "createdAt": "2026-09-02T17:00:00",
  "updatedAt": "2026-09-02T17:10:30"
}
```

任务状态冻结为：

```text
QUEUED      等待分配
ASSIGNED    已分配打印机，等待安全确认/启动
READY       文件已上传到设备，等待开始
PRINTING    打印中
PAUSED      已暂停
RECONCILING 设备结果未知，等待人工核对
COMPLETED   已完成
FAILED      失败
CANCELLED   已取消
```

状态流转：

```text
QUEUED -> ASSIGNED -> READY -> PRINTING -> PAUSED -> PRINTING
QUEUED/ASSIGNED/READY/PAUSED -> CANCELLED
PRINTING -> COMPLETED
PRINTING -> FAILED
FAILED -> QUEUED（重试）
```

废弃状态：`PENDING`、`PREPARING` 作为任务状态、`CANCELED`。`PREPARING` 只可用于打印机状态。
设备上报取消时允许 `PRINTING -> CANCELLED`；设备上报急停或 idle 但没有终态证据时进入 `RECONCILING`，保留打印机绑定；用户通过当前任务删除接口取消打印中的任务仍返回 HTTP 422。

已有 Docker 数据卷不会自动执行新增 SQL。升级已有数据库前请先备份，然后手动执行：

```bash
mysql -u root -p farm < src/main/resources/db/migration/04-normalize-print-job-status.sql
mysql -u root -p farm < src/main/resources/db/migration/05-normalize-printer-firmware-type.sql
mysql -u root -p farm < src/main/resources/db/migration/06-add-printer-status-history.sql
mysql -u root -p farm < src/main/resources/db/migration/07-v2-dispatch-plan.sql
mysql -u root -p farm < src/main/resources/db/migration/08-v2-atomic-printer-binding.sql
mysql -u root -p farm < src/main/resources/db/migration/09-v2-print-job-idempotency.sql
```

`02-current-schema.sql` 已改为按 `information_schema` 检查列和索引后再添加，可重复执行；`04`、`05` 的状态/协议规范化更新也只作用于旧值，`06` 使用 `CREATE TABLE IF NOT EXISTS`。这些脚本不会自动作用于已有 Docker 数据卷，执行前仍必须备份。

实体、Mapper 与 SQL 字段已完成静态核对：用户、打印机、打印文件、打印任务和打印机状态历史的当前字段均能在初始化表或对应增量脚本中找到；`operator_id`、文件目录/对象存储字段、`is_safe_to_print` 和状态历史表属于增量升级内容。历史 `V*.sql` 文件不代表 Spring 会自动执行的迁移，已有数据卷仍需按 `OPERATIONS.md` 手工升级并在真实 MySQL 中验收。

### 6.3 PrintFileVO

```json
{
  "id": 20,
  "parentId": null,
  "folder": false,
  "originalName": "demo.gcode",
  "fileSize": 123456,
  "userId": 1,
  "createdAt": "2026-09-02T17:00:00",
  "estTime": 3600,
  "materialType": "PLA",
  "nozzleSize": 0.4,
  "filamentWeight": 12.5,
  "filamentLength": 3.00,
  "nozzleTemp": 210,
  "bedTemp": 60,
  "layerHeight": 0.2,
  "printCount": 0,
  "successRate": 0.0
}
```

- `fileSize`：Long，单位字节。
- `estTime`：Integer，单位秒；字段名以当前 VO 实际 JSON 为准，不使用 `estimatedSeconds`。
- `filamentWeight`：BigDecimal，单位克。
- `filamentLength`：BigDecimal，单位米。
- G-code 中 `filament used [mm]` 或兼容的毫米字段，无论数值大小都会在入库时除以1000转换为米；显式以 `m/meter` 给出的兼容字段按米保存。
- `PrintFileVO`/`PrintFilePreviewVO` 的切片温度字段（`nozzleTemp`、`bedTemp` 及首层温度）为 Integer，单位摄氏度；实时设备状态 DTO 的温度字段仍为 Double。
- `successRate`：BigDecimal，范围 0-100，表示百分比。
- 文件列表统计口径：`printCount` 只统计已结束的 `COMPLETED/FAILED/CANCELLED` 任务；`successRate` 为 `COMPLETED/(COMPLETED+FAILED)` 的百分比，取消任务不计入分母，`QUEUED/ASSIGNED/READY/PRINTING/PAUSED` 不参与统计。
- `folder` 是文件对象唯一的目录布尔字段，禁止依赖或发送旧字段 `isFolder`；实体内部仍使用数据库列 `is_folder`。
- 文件对象无论来自上传、分页还是目录查询，`folder` 始终为 JSON 布尔值；实体目录标记为空时按普通文件输出 `false`，不会返回 `null`。
- `rustfsKey`、`safeName`、`fileUrl`、内部存储路径和 API Key 不属于前端 DTO；下载必须调用独立的 `/download` 接口获取短期预签名 URL。

### 6.4 时间、数字和金额

- REST 的 `LocalDateTime` 使用 ISO-8601 字符串：`yyyy-MM-dd'T'HH:mm:ss`。
- 当前系统默认时区为服务端本地时区，现阶段按 Asia/Shanghai 使用。
- WebSocket `timestamp` 使用 Unix epoch 毫秒。
- ID 使用 Long，前端 JavaScript 建议按字符串安全处理超大 ID。
- REST `PrintJobVO.progress` 使用 BigDecimal，范围 0-100；WebSocket 消息中的 `progress` 保持 JSON 数值，前端按 0-100 的小数处理。
- 金额字段当前不存在；如果以后增加，使用整数分或 Decimal 字符串，禁止使用 Double 表示金额。

## 7. WebSocket 契约

### 7.1 连接

```text
ws://<server-host>:8080/ws/farm-status
```

连接时必须携带登录返回的 JWT：

```text
ws://<server-host>:8080/ws/farm-status?token=<JWT>
```

WebSocket 握手必须携带登录接口返回的 JWT。浏览器客户端使用查询参数传递：`/ws/farm-status?token=<JWT>`；缺少、无效或过期 Token 的连接会被服务端拒绝。服务端还会检查 Redis 中的用户禁用标记：已禁用用户不能使用既有 JWT 建立 WebSocket 连接；禁用状态查询异常时同样拒绝连接，不允许鉴权依赖故障时放行。WebSocket 握手在升级后无法返回 REST JSON，客户端应将关闭视为鉴权失败并重新请求 `/auth/me` 或重新登录。当前连接仍是农场级广播，后续如需按打印机订阅再增加细粒度隔离。

### 7.2 消息格式

当前消息版本固定为 `version=1`；前端应先按 `version` 分支解析，未知版本应保留 REST 快照兜底。每条业务消息还带唯一 `eventId` 和单进程递增 `sequence`；重连后不能依赖 sequence 连续，必须重新请求 REST 快照。前端在已连接期间发现 sequence 断档时，也应重新请求打印机 REST 快照恢复当前状态，不能伪造缺失事件。

统一使用：

```json
{
  "version": "1",
  "type": "PRINTER_STATUS",
  "eventId": "evt-uuid",
  "sequence": 42,
  "printerId": 403,
  "timestamp": 1756790000000,
  "data": {
    "unifiedState": "PRINTING",
    "state": "printing",
    "filename": "demo.gcode",
    "progress": 35.5,
    "printDuration": 120.0,
    "totalDuration": 340.0,
    "filamentUsed": 1500.0,
    "toolTemperature": 210.0,
    "toolTarget": 210.0,
    "bedTemperature": 60.0,
    "bedTarget": 60.0
  }
}
```

服务端当前实际发送的顶层字段为 `version`、`type`、`eventId`、`sequence`、`printerId`、`timestamp`、`data`；`eventId` 每条消息唯一，`sequence` 为单进程递增值。客户端重连或发现版本/序列不连续时，应重新请求 REST 快照。

消息类型冻结为：

```text
SNAPSHOT          连接后的全量快照
PRINTER_STATUS    单台打印机状态变化
PRINTER_OFFLINE   打印机离线
JOB_STATUS        任务状态变化
```

当前已实现消息类型和 `FarmStatusMessage` 顶层结构，并由服务端校验类型、时间戳、关联 ID 和敏感字段。鉴权成功后服务端发送一次 `SNAPSHOT`，其 `data.printers` 使用安全 `PrinterVO`，没有打印机时返回空数组。监控任务通过 `WebSocketEventPublisher` 发布 `PRINTER_STATUS` 和 `PRINTER_OFFLINE`：状态/进度数据变化时推送，连续离线只推送一次，设备恢复后重新推送状态。任务服务和监控任务在任务状态 `updateById` 成功后发布 `JOB_STATUS`；有数据库事务时，四类业务事件统一在事务提交后广播，事务回滚不广播；无事务的监控场景直接发布。没有绑定打印机的排队任务不发送任务事件。服务端按 `farm.websocket.heartbeat-interval`（Spring Duration，默认 `30s`）发送协议级 Ping，连接上限按 `farm.websocket.max-connections` 配置（默认 100），失败连接会清理。2026-09-03 的历史容器冒烟曾收到 46 台打印机的快照，后续当前实例验证为 49 台；两者均为当时数据库设备数量，不属于固定契约。前端已完成自动重连、告警展示、重复/乱序事件丢弃、sequence 断档后的 REST 快照恢复和客户端测试，浏览器端完整端到端与真实设备事件仍待后续联调。本阶段已完成握手鉴权，生产环境不再允许匿名广播。

## 8. 打印机协议适配约定

HTTP API 不因为 Klipper 或 RRF 改变。当前已完成协议领域模型、Adapter 接口、Factory、Klipper Adapter、RRF Adapter、打印机控制 Service、任务服务和监控任务迁移；后端内部根据 `firmwareType` 选择适配器：

```text
PrinterProtocolAdapter
├── KlipperMoonrakerAdapter
└── RrfAdapter
```

适配器至少提供：

```text
getStatus()
pause()
resume()
cancel()
emergencyStop()
uploadFile()
startPrint()
```

Controller -> Service -> `PrinterProtocolAdapter` -> 具体协议客户端。

`RrfApiClient` 已按官方资料实现独立的 HTTP 调用链：使用 `rr_connect?password=...&sessionKey=yes` 建立短会话，使用 `X-Session-Key` 调用 `rr_model`、`rr_gcode` 和 `rr_upload`，操作结束后调用 `rr_disconnect`。状态读取 `state.status`、`job.file.fileName`、文件大小/位置和已确认的任务字段；控制动作使用 `M25`、`M24`、`M0`、`M112`，上传后启动使用 `M32`。

`RrfApiClientTest` 已通过可复现 HTTP Mock 测试，覆盖会话、状态/进度、G-code、原始文件上传、设备错误码和密码错误分类。这里的测试证明协议调用边界和解析逻辑，不等同于真实 RRF 3.7 物理验收。`apiKey` 在 RRF 适配中作为设备密码使用，不能当作长期 session key；真实目标 `192.168.0.77` 已完成控制、探针上传和 `M32` 启动请求的响应级验证，并完成 Farm 安全任务链路，但仍需确认固件构建、standalone/SBC 模式、`0:/gcodes` 文件完整可见性、会话限制以及生产任务下 `M0/M112/M32` 的现场副作用。

禁止在 Controller 中直接注入 `MoonrakerApiClient`，也禁止仅通过修改 URL 假装支持 RRF 3.7。

## 9. 运行与联调

### 9.1 启动

```bash
docker compose up -d
mvn spring-boot:run
```

开发环境默认：

```text
MySQL:  localhost:3306
Redis:  localhost:6379
RustFS: localhost:9000
应用:   localhost:8080
```

### 9.2 数据库

新 Docker 数据卷初始化脚本顺序：

```text
src/main/resources/db/migration/farm.sql
src/main/resources/db/migration/02-current-schema.sql
src/main/resources/db/migration/03-remove-customer-role.sql
src/main/resources/db/migration/04-normalize-print-job-status.sql
src/main/resources/db/migration/05-normalize-printer-firmware-type.sql
src/main/resources/db/migration/06-add-printer-status-history.sql
src/main/resources/db/migration/07-v2-dispatch-plan.sql
src/main/resources/db/migration/08-v2-atomic-printer-binding.sql
src/main/resources/db/migration/09-v2-print-job-idempotency.sql
src/main/resources/db/migration/10-v2-dispatch-resource-fingerprint.sql
```

已有数据卷不会因为修改 SQL 自动升级。升级前必须备份，并手工执行经过确认的增量 SQL。2026-09-03 已在当前开发 Docker 数据卷完成一次备份后迁移：记录数量保持为用户 2、打印机 46、文件 1、任务 3；旧任务状态 `MANUAL` 已规范为 `QUEUED`，旧协议值 `Klipper` 已规范为 `KLIPPER`，新增字段和 `farm_printer_status_history` 已核对存在。该记录不代表生产环境已迁移，生产仍须按 `OPERATIONS.md` 执行并保留备份。

### 9.2.1 Local Edition

使用 `--spring.profiles.active=local` 启动时，业务数据写入 `${FARM_DATA_DIR:./data}/farm.db`，文件写入 `${FARM_DATA_DIR:./data}/files`。Local profile 使用 `FileStorage` 的本地实现和进程内缓存/锁，不连接 Redis、RustFS 或 MySQL；初始表由 `db/migration/local-schema.sql` 以 `CREATE TABLE IF NOT EXISTS` 方式初始化。MyBatis 自定义 SQL 已为 SQLite 提供筛选、统计、搜索和 Upsert 方言分支。REST 和 WebSocket 地址、响应字段和任务状态不因部署形态改变。Local 下载链接由受保护的 `/api/v1/print-files/storage?key=...` 提供，客户端不得把磁盘路径当作 URL。

### 9.3 测试环境

```bash
mvn test
```

测试使用 H2 随机端口，关闭定时任务和 WebSocket；MockMvc 已覆盖核心路由的 401/403/400/404/500 响应和管理员委托路径，文件/任务/打印机 Service 归属与异常测试、WebSocket 生命周期/事件/Ping 测试均已增加。2026-09-03 已使用真实 Docker MySQL、Redis、RustFS 启动 dev 应用完成一次冒烟验证：`/actuator/health` 返回 `UP`，管理员登录、`/auth/me`、打印机分页、文件分页和任务队列均返回 200，分页总数与数据库记录一致；临时 G-code 的上传、预览、预签名下载 URL 和删除也均返回 200，清理后文件记录数量恢复。该验证未连接真实 Klipper/RRF 打印机，也未完成上传到打印完成的完整链路。

### 9.3.1 真实本地请求级联调记录（2026-09-03）

运行前提：Docker 仅启动 `farm-mysql`、`farm-redis`、`farm-rustfs`，Java 后端以 `dev` Profile 运行在 `127.0.0.1:8080`；未开启打印机监控任务，因此本次不会轮询或控制 RRF 设备。

请求样例和结果：

| 功能 | 请求样例 | 实际结果 |
|---|---|---|
| 健康检查 | `GET /actuator/health` | HTTP 200，`status=UP` |
| 登录 | `POST /api/v1/auth/login`，JSON `{ "username": "admin", "password": "<本地密码>" }` | HTTP 200，返回 `data.token`、`userId=1`、`role=ADMIN`；密码和 Token 不记录 |
| 当前用户 | `GET /api/v1/auth/me`，携带 `Authorization: Bearer <JWT>` | HTTP 200，返回用户 ID、用户名和角色 |
| 打印机分页 | `GET /api/v1/printers/page?pageNum=1&pageSize=5` | HTTP 200，`data.total=46`，本页 5 条 |
| 文件分页 | `POST /api/v1/print-files/page`，JSON `{ "pageNum": 1, "pageSize": 5 }` | HTTP 200，`data.total=1`，本页 1 条 |
| 任务队列 | `GET /api/v1/print-jobs/queue` | HTTP 200，返回 2 个队列任务 |
| 实时状态 | `WS /ws/farm-status?token=<JWT>` | 握手成功，收到 `type=SNAPSHOT`，包含 46 台打印机和有效时间戳 |

前端对应文件：`/home/codex/workspace/farm-ui/src/utils/request.js` 负责 Token 和统一响应处理；`src/api/user.js`、`src/api/printer.js`、`src/api/printFile.js`、`src/api/job.js` 负责 REST；`src/views/BatchDispatch.vue` 负责用户确认的批量上传/预览/派发；`src/stores/printer/realtimeStore.js` 负责 `/ws/farm-status` 的连接、快照、增量消息和断档恢复。

本次验证是健康、认证、查询和 WebSocket 握手冒烟，不包含 RRF 控制、文件上传到打印机、启动打印、暂停、急停或完整打印完成链路。

### 9.3.2 第一版只读验收补充（2026-09-03）

在同一管理员会话中，REST 打印机分页返回 `total=46`，随后通过前端 Vite 代理（本次实际端口 `5174`）连接 WebSocket，`SNAPSHOT.data.printers` 返回 46 条，数量一致；首条快照设备状态为 `OFFLINE`，且未包含 `apiKey`。管理员用户列表中已有 `OPERATOR` 账号；任务分页返回 3 条任务。前端批量派发页面已完成构建，sequence 处理和断档恢复纯函数测试通过；该结果支持 T10.3、T10.6、T10.8、T10.9 和 T10.10 的代码/请求级验收；由于开发环境关闭监控任务且未操作真实设备，不宣称自然状态增量和完整打印链路已完成。

T10.1 补充验收：2026-09-03 使用真实管理员会话调用 `/auth/admin/users` 创建、更新、禁用、启用接口均返回 HTTP 200；创建的临时操作员记录 ID `3` 在验收结束时再次禁用，未修改既有账号，密码未写入文档或日志。

真实 RRF 目标 `192.168.0.77` 已由管理员登记为设备 ID `564`、协议 `RRF`、空密码。2026-09-03 仅对该 IP 验证了 `M25/M24/M0/M112`、Farm 上传的无动作探针文件和 `M32` 启动请求，均返回成功；Farm 后端暂停/急停返回 200，无任务时恢复/取消返回 422。探针任务后设备返回 `state.status=idle` 并记录 `lastFileName` 和完成位置；`M112` 后通过 `M999` 复位成功。设备仍返回 `isEmulated=true`、`boardType=unknown`，所以生产任务的运动、加热和宏副作用仍待现场验收。此前 `192.168.0.62` 的 ID `563` 仅为误输入产生的历史测试记录，不作为真实目标。

T9.6/T10.5 现场协作前提：当前开发环境保持 `farm.monitor.enabled=false`、`farm.scheduler.enabled=false`，已完成无动作探针和控制接口的响应级验证，但尚未完成真实打印中的自然完成、暂停、恢复、取消及 WebSocket 状态链路。后续需要用户确认一份可安全执行的真实 G-code，并在打印机现场观察运动/加热、暂停恢复取消和急停复位结果；Codex 不能仅凭 HTTP `200` 判定设备物理动作成功。

2026-09-03 现场补充：使用 `.77` 和无运动/无加热/无挤出的等待文件完成任务 6 的暂停、恢复、取消控制验证，以及任务 7 的设备端完成验证。任务 7 在 RRF 读取为 `idle`、`job.timesLeft` 为空后，Farm 任务精确收尾为 `COMPLETED`、进度 100% 并解绑。由于 `farm.monitor.enabled=false`，暂停后的 Farm 状态和完成状态没有由监控任务自然写回；临时开启监控不会自动开启 scheduler，但仍未作为最终自然同步验收环境。任务 6 取消后 RRF 对长等待命令短暂保持 `processing`，随后对 `.77` 执行 `M112`/`M999` 复位为 `idle`；该行为需要在隔离队列和监控开启后继续确认。

2026-09-03 `.88` 只读监控补充：当前监控白名单切换为 Farm 打印机 ID `565`（`192.168.0.88`），协议为 RRF。后端重启后通过 `/api/v1/printers/by-ip/192.168.0.88` 读取到 `status=IDLE`，直接读取 `GET /rr_model?key=state` 返回 HTTP 200、RRF `status=idle`、`machineMode=FFF`。本次仅验证网络、协议读取和监控状态同步，没有执行上传、启动、暂停、取消或急停。

2026-09-03 本地业务联调补充：运行中的 dev 后端完成 health、登录、`/auth/me`、打印机分页、文件分页、文件预览、任务队列和批量预览验证，均返回成功；批量预览返回 `planId/version/confirmationToken`，未创建任务、占用打印机或调用设备。临时单文件上传和批量上传各成功 1 项，随后通过文件删除接口清理，未残留本次测试文件。该记录覆盖本地存储/对象存储和批量计划的请求级链路，不代表真实设备控制验收。

同日补充校验：前端 `npx eslint src --no-cache`、`npm test`（13 项）和 `npm run build` 均通过；`docker compose --env-file .env.server.example -f docker-compose.server.yml config -q` 与开发版 `docker-compose.yml config -q` 均通过。Server Edition 配置校验使用示例值，仅证明 Compose 结构和变量引用完整，不代表示例密钥可直接用于生产。

2026-09-03 `.77` v2 现场链路补充（Farm 打印机 ID `564`）：使用文件 `farm_rrf_completion_acceptance.gcode`（116 字节，仅注释、`G90`、`M83`、`G4 P20000`，无移动/加热/挤出）创建 `jobId=8`，完成安全派发、确认、上传和启动；RRF 曾返回 `processing`（进度约 91.38%），随后回到 `idle`，由于没有足够终态证据，Farm 正确将任务置为 `RECONCILING`，之后清理为 `CANCELLED` 并解除当前绑定。使用 `farm_rrf_control_acceptance.gcode`（110 字节，`G4 P180000`）创建 `jobId=9`，真实验证结果如下：安全创建/派发/确认/启动均 HTTP 200；`processing -> paused` 的暂停返回 200 且任务为 `PAUSED`；`paused -> processing` 的恢复返回 200 且任务为 `PRINTING`；取消返回 200、任务为 `CANCELLED` 且 Farm 当前绑定解除，但设备因长 `G4` 尚未立即停止，随后仍短暂返回 `processing`，不能把取消 HTTP 200 解释为设备已停止。对该残留执行一次急停，接口返回 HTTP 503，但 RRF 随后进入 `starting`，约十余秒后恢复 `idle`，监控状态历史记录了 `PRINTING(90%) -> PREPARING(starting) -> IDLE`，Farm 打印机最终为 `IDLE` 且 `currentJobId=null`。本次没有发送会导致运动、加热或挤出的 G-code，也没有使用 `.62`；正常生产文件的物理打印、取消即时停止语义和浏览器 WebSocket 实时展示仍未验收。

数据一致性补充：2026-09-03 已对当前开发库执行一次有备份的幽灵绑定修复。发现任务 `1` 的 `printer_id=289` 不再存在，修复后任务解除打印机绑定并置为 `RECONCILING`，审计记录写入 `farm_binding_repair_audit`，残留孤儿绑定为 0。修复脚本为 `scripts/repair-ghost-bindings.sql`；它不会删除任务、自动派单或调用设备。该结果只代表当前开发库，生产库仍须先备份后单独核对。

同日再次进行 8080 只读冒烟时，发现当前已有 MySQL 数据卷尚未执行 v2 的 07-10 增量迁移，导致任务队列查询因缺少 `farm_print_job.idempotency_key` 返回 500。已先生成 `/tmp/farm-before-v2-migrations-20260903.sql`，再执行 07-10 脚本；07/09/10 的表字段已核对存在，任务队列恢复 HTTP 200。10 号脚本同时修正为基于 `information_schema` 的可重复 MySQL 写法。以后新环境和已有数据卷都必须按 `OPERATIONS.md` 先备份、再执行增量迁移。

### 9.4 真实打印机

开发环境默认关闭：

```yaml
farm.monitor.enabled=false
farm.scheduler.enabled=false
```

没有真实打印机时不要打开监控任务，否则会持续访问不存在的设备。当前 Moonraker 模拟接口只在 `dev/test` Profile 加载，不代表完整 Klipper 或 RRF 模拟器。

监控任务已增加单轮巡检互斥，上一轮未结束时会跳过重叠轮次；单台设备的离线异常 WARN 日志按 60 秒限频，恢复在线后重置限频状态。该保护由 `PrinterMonitorAdapterTest` 覆盖，但未在当前关闭调度的 dev 环境对全部设备做压力轮询。

## 10. 当前已知差异和验收条件

当前契约和剩余验收状态：

1. `PENDING/QUEUED` 统一为 `QUEUED`。
2. `CANCELED/CANCELLED` 统一为 `CANCELLED`。
3. `POST /print-jobs` 与旧 `/create` 的兼容策略。
4. 统一分页返回字段。
5. 已修复 `BusinessException` 错误码被丢失的问题。
6. 已统一 HTTP 401、403、404、500 和 JSON 错误格式；MockMvc 已覆盖核心错误响应，真实数据库异常仍需现场联调。
7. 已增加文件和任务的服务层资源归属校验；打印机仍是农场共享资源。
8. 返回 VO，禁止直接暴露 `apiKey` 和 `rustfsKey`。
9. 文件分页已支持名称、材质筛选。
10. 新建文件夹已正确设置用户归属并校验父目录。
11. WebSocket 已完成握手鉴权、四类 `type` 消息、初始快照、离线/恢复事件、任务失败原因、协议级 Ping 保活和异常连接清理；2026-09-03 已在真实启动的本地后端完成 JWT 握手和 `SNAPSHOT` 请求级验证，前端已完成告警展示、序号断档恢复和客户端测试，浏览器端完整端到端仍待联调。
12. 为 ADMIN/OPERATOR 增加 401/403 集成测试。
13. Klipper 和 RRF 都通过协议适配器接入；RRF 已有可复现 HTTP 协议测试，并已在真实目标 `192.168.0.77` 完成控制、上传、Farm 安全任务和启动请求的响应级验证；完整生产任务物理链路仍待现场验收。

RRF 3.7 协议证据已登记在 [RRF 3.7 协议证据](.kiro/specs/printer-protocol-and-websocket/rrf-3.7-protocol-evidence.md)。2026-09-03 已对真实目标 `192.168.0.77` 完成空密码、只读对象模型、控制、上传、Farm 安全任务和启动请求探测；该设备响应 `isEmulated=true`、`boardType=unknown`，且未返回 `sessionKey`，后端已兼容此类响应。响应级验证和无动作探针任务已完成，但完整生产物理打印链路仍不能视为验收。此前 `192.168.0.62` 仅为误输入产生的历史测试记录。前端真实仓库为 `/home/codex/workspace/farm-ui`。

## 11. 前端开发优先顺序

第一阶段可以直接联调：

```text
登录
打印机分页和看板
文件上传、分页、下载、文件夹
任务队列、分页、创建、取消
安全派发、安全确认、安全启动
暂停、急停
```

后端增强接口已完成，当前可继续联调：

```text
打印机详情、历史、统计
恢复和取消控制接口
任务重试、重新排队、调整优先级
文件目录树、文件关联任务和安全预览
WebSocket 新消息协议、JWT 握手和协议级 Ping
RRF 3.7 适配器与可复现 HTTP Mock
```

上述接口可以按本文契约直接进行前端联调；真实 RRF/Klipper 设备副作用和浏览器端完整端到端仍属于现场验收项。

### v3 规划提示（当前不作为 v2 接口契约）

当前后端仍采用 Farm 主动读取设备状态，WebSocket 仅负责 Farm 到客户端的推送。v3 如优化设备同步，应增加“命令请求/设备确认”语义：控制接口返回成功只代表设备接受请求，取消或急停不得在未确认设备停止前立即释放绑定；长时间 G-code 阻塞时应进入可恢复中间态并记录确认超时。监控可按空闲、打印中、离线状态自适应轮询，并在控制操作后短时加密确认；若某种固件或网关提供可靠事件上报，再通过协议适配器接入，轮询仍保留为心跳和一致性兜底。该规划不改变 v2 手动上传、手动分配、现场确认和手动启动的接口。

## 12. 后端代码定位与文档来源

### 12.1 Controller

| 功能 | Controller |
|---|---|
| 认证、用户 | `src/main/java/com/example/farm/controller/UserController.java` |
| 打印机 CRUD、扫描、位置 | `src/main/java/com/example/farm/controller/PrinterController.java` |
| 文件库、上传、下载、文件夹 | `src/main/java/com/example/farm/controller/PrintFileController.java` |
| 任务、队列、安全打印 | `src/main/java/com/example/farm/controller/PrintJobController.java` |
| 暂停、急停 | `src/main/java/com/example/farm/controller/PrinterControlController.java` |
| WebSocket | `src/main/java/com/example/farm/controller/WebSocketServer.java` |
| 开发用 Moonraker 模拟接口 | `src/main/java/com/example/farm/controller/MoonrakerMockController.java` |

### 12.2 Service、Mapper 和实体

```text
Service 接口：src/main/java/com/example/farm/service/
Service 实现：src/main/java/com/example/farm/service/impl/
Mapper：      src/main/java/com/example/farm/mapper/
Mapper XML：  src/main/resources/mapper/
实体与 DTO：  src/main/java/com/example/farm/entity/
```

主要实现类：

```text
UserServiceImpl
PrinterServiceImpl
PrintFileServiceImpl
PrintJobServiceImpl
PrinterCacheServiceImpl
```

设备协议客户端位于：

```text
src/main/java/com/example/farm/common/utils/MoonrakerApiClient.java
src/main/java/com/example/farm/protocol/RrfApiClient.java
```

`MoonrakerApiClient` 和 `RrfApiClient` 由 `PrinterProtocolAdapterFactory` 选择的具体适配器调用；Controller 不直接调用协议客户端。

### 12.3 Swagger/OpenAPI

运行服务后访问：

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/v3/api-docs
```

Swagger 反映当前 Controller 上的 OpenAPI 注解；WebSocket `@ServerEndpoint` 不会作为 REST OpenAPI 路由展示。本文第 4 节正式 API 与当前 Controller 对应，批量接口的请求体、响应体、枚举和 Bearer Token 说明已同步。新增或修改 Controller 后，必须同步本文并检查 `/v3/api-docs`。

本文是当前唯一的接口交接文档；接口联调以本文、实际 Controller、`SecurityConfig` 和 `/v3/api-docs` 为准。

### 12.4 测试状态

当前测试包括：

```text
src/test/java/com/example/farm/FarmApplicationTests.java
src/test/java/com/example/farm/service/PrintJobOwnershipTest.java
src/test/java/com/example/farm/service/PrintFileOwnershipTest.java
src/test/java/com/example/farm/controller/WebSocketSecurityTest.java
src/test/java/com/example/farm/mapper/PrintFileMapperTest.java
```

上下文测试使用 `test` Profile，关闭任务和 WebSocket；归属测试使用服务层单元测试；`PrintFileMapperTest` 使用 H2 的 MySQL 模式覆盖关键 Mapper SQL；WebSocket 测试覆盖生命周期、业务事件和协议级 Ping。当前没有：

- MySQL/RustFS/真实 Redis 联调测试；
- Klipper 或 RRF 设备测试；
- 浏览器端真实前端与后端的完整端到端测试；
- 上传文件到设备完成打印的完整端到端测试。

因此本文中的“现有接口”表示源码中存在，不表示已经完成真实环境验收。
