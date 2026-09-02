# Farm 前后端接口交接与契约文档

版本：v1.0-draft  
更新时间：2026-09-02  
适用范围：Farm 本地 3D 打印农场服务端与浏览器/客户端

> 本文以当前 Java 源码为准。标记为“现有”的接口已经有 Controller；标记为“规划”的接口是前后端同步开发前冻结的目标契约，当前尚未全部实现。前端不得把规划接口当成当前可调用接口。

项目执行入口：[PROJECT.md](./PROJECT.md)；Kiro 需求、设计和任务清单位于 `.kiro/specs/farm-v1/`。

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

正式 WebSocket 地址确定为 `/ws/farm-status`。`/ws` 是历史约定，不作为新前端地址；如果已有前端无法立即修改，后端可在兼容阶段额外提供 `/ws` 别名。

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
| 500 | 5001 | MySQL 错误 |
| 500 | 5002 | Redis 错误 |
| 500 | 5003 | RustFS/对象存储错误 |
| 503 | 5004 | 外部设备网络错误 |

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

Service 层仍使用 MyBatis-Plus `Page/IPage`，Controller 已通过 `PageResult` 转换为本文结构。分页参数要求 `pageNum>=1`、`1<=pageSize<=100`，非法参数返回 HTTP 400、`code=400`。

任务状态已统一为 `QUEUED`、`ASSIGNED`、`READY`、`PRINTING`、`PAUSED`、`COMPLETED`、`FAILED`、`CANCELLED`。新建任务进入 `QUEUED`；调度器只负责进入 `ASSIGNED`，必须在设备调用成功后才进入 `PRINTING`。非法状态流转返回 HTTP 422、`code=422`。历史数据库中的 `PENDING`、`MANUAL` 会兼容转换为 `QUEUED`，`CANCELED` 会转换为 `CANCELLED`。

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

`expiresIn` 单位为秒。当前没有 refresh token 和 logout 黑名单。

### 3.2 用户管理

| 方法 | 地址 | 权限 | 请求 | 返回 |
|---|---|---|---|---|
| POST | `/auth/register` | ADMIN | 用户注册 DTO | `Result<Long>` |
| POST | `/auth/admin/users` | ADMIN | 用户注册 DTO | `Result<Long>` |
| GET | `/auth/admin/users` | ADMIN | `pageNum,pageSize,username,role,email` | 分页用户，不能包含密码 |
| PUT | `/auth/admin/users/{userId}` | ADMIN | 用户更新 DTO | `Result<null>` |
| POST | `/auth/admin/users/{userId}/disable` | ADMIN | Path ID | `Result<null>` |
| POST | `/auth/admin/users/{userId}/enable` | ADMIN | Path ID | `Result<null>` |
| GET | `/auth/{userId}/profile` | 本人 | 无 | 用户资料 |
| PUT | `/auth/{userId}/profile` | 本人 | 邮箱、手机号 | `Result<null>` |
| POST | `/auth/{userId}/change-password` | 本人 | 旧密码、新密码、确认密码 | `Result<null>` |

密码规则由后端强制校验：6-20 位，必须包含大写字母、小写字母和数字。`CUSTOMER` 不再使用。

## 4. 当前已有业务接口

以下接口以当前 Controller 为准，均使用 `Result<T>` 包装。

### 4.1 打印机

| 方法 | 地址 | 权限 | 参数 | 返回 |
|---|---|---|---|---|
| GET | `/printers/page` | ADMIN/OPERATOR | Query：`pageNum,pageSize,name,status` | `PageResult<PrinterVO>`，不含 apiKey |
| GET | `/printers/{id}` | ADMIN/OPERATOR | Path：打印机 ID | `PrinterDetailVO`：安全配置、缓存实时状态和当前任务摘要 |
| POST | `/printers/add` | ADMIN | 打印机配置 | `Result<null>` |
| PUT | `/printers/update` | ADMIN | 包含 `id` 的打印机配置 | `Result<null>` |
| DELETE | `/printers/delete/{id}` | ADMIN | Path ID | `Result<null>` |
| GET | `/printers/scan` | ADMIN | Query：`subnet` | 扫描结果数组 |
| POST | `/printers/batch-add` | ADMIN | 扫描结果数组 | 批量新增/更新统计及逐项结果 |
| GET | `/printers/by-mac/{macAddress}` | ADMIN/OPERATOR | Path MAC | `PrinterVO` 或 null |
| GET | `/printers/by-ip/{ipAddress}` | ADMIN/OPERATOR | Path IP | `PrinterVO` 或 null |
| PUT | `/printers/positions` | ADMIN | 位置更新数组 | `Result<null>` |
| GET | `/printers/unallocated` | ADMIN/OPERATOR | Query：`keyword` | `PrinterVO[]` |

### 4.2 文件

| 方法 | 地址 | 权限 | 参数 | 返回 |
|---|---|---|---|---|
| POST | `/print-files/upload` | ADMIN/OPERATOR | Multipart：`file` | `PrintFileVO`，不含 rustfsKey |
| POST | `/print-files/page` | ADMIN/OPERATOR | JSON：分页和文件筛选 | `PageResult<PrintFileVO>` |
| GET | `/print-files/{id}/download` | ADMIN/OPERATOR | Query：`expires` | 预签名 URL 字符串 |
| DELETE | `/print-files/{id}` | ADMIN/OPERATOR | Path ID | `Result<null>` |
| DELETE | `/print-files/batch` | ADMIN/OPERATOR | `{"ids":[1,2]}`，最多100个 | `Result<BatchDeleteResult>`，包含每个 ID 的成功/失败原因 |
| GET | `/print-files/folder/content` | ADMIN/OPERATOR | Query：`parentId` | `PrintFileVO[]` |
| POST | `/print-files/folder/create` | ADMIN/OPERATOR | `parentId,folderName` | `PrintFileVO` |

### 4.3 打印任务

| 方法 | 地址 | 权限 | 参数 | 返回 |
|---|---|---|---|---|
| GET | `/print-jobs/queue` | ADMIN/OPERATOR | 无 | `PrintJobVO[]` |
| POST | `/print-jobs/page` | ADMIN/OPERATOR | JSON：分页、状态、打印机、时间 | `PageResult<PrintJobVO>` |
| GET | `/print-jobs/{id}` | ADMIN/OPERATOR | Path ID | `PrintJobVO` |
| POST | `/print-jobs/create` | ADMIN/OPERATOR | `fileId,priority` | 新任务 ID |
| DELETE | `/print-jobs/{id}` | ADMIN/OPERATOR | Path ID | 取消任务 |
| POST | `/print-jobs/{jobId}/assign` | ADMIN/OPERATOR | Query：`printerId` | 分配并启动 |
| POST | `/print-jobs/safe/assign` | ADMIN/OPERATOR | `jobId,printerId` | 安全派发 |
| POST | `/print-jobs/safe/confirm` | ADMIN/OPERATOR | `printerId,operatorId?` | 安全确认 |
| POST | `/print-jobs/safe/start` | ADMIN/OPERATOR | `jobId,operatorId?,action?` | 启动或仅上传 |

### 4.4 设备控制

| 方法 | 地址 | 权限 | 参数 | 返回 |
|---|---|---|---|---|
| POST | `/control/{id}/pause` | ADMIN/OPERATOR | Path 打印机 ID | `Result<null>` |
| POST | `/control/{id}/emergency-stop` | ADMIN/OPERATOR | Path 打印机 ID | `Result<null>` |

### 4.5 当前输入校验约定

- 打印机名称最多100个字符；IP 必须为 IPv4；MAC 支持冒号或连字符格式；固件类型只能是 `KLIPPER` 或 `RRF`（大小写兼容）；网格范围为行 `1-4`、列 `1-12`。
- 创建任务的 `fileId` 必须为正数，`priority` 范围为 `0-100`。
- 派发、确认安全、启动任务的 ID 必须为正数；`action` 只能是 `START_PRINT` 或 `UPLOAD_ONLY`。`operatorId` 仍兼容接收，但后端忽略其值并使用 JWT 当前用户。
- 文件夹名称最多100个字符，不允许 `/`、`\\`、控制字符及 `:*?\"<>|`；`parentId` 必须为正数或省略表示根目录。
- 批量添加打印机、批量删除文件、批量更新位置单次最多100项。批量删除返回 `items`，每项包含 `id`、`success`、`reason`；批量添加返回 `items`，每项包含 `index`、地址、成功标志和原因。
- 文件上传扩展名从 `farm.file.allowed-types` 读取，默认允许 `gcode,g,3mf,stl`；开发环境上限200MB，生产环境上限1GB。空文件、非法文件名、超限和不支持类型统一返回 HTTP 400；RustFS 失败返回 HTTP 503、业务码 `5003`。

## 5. 未完成接口与冻结后的目标规范

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
| GET | `/printers/{id}/statistics` | ADMIN/OPERATOR | `from,to` | 统计 DTO | 规划 |
| POST | `/control/{id}/resume` | ADMIN/OPERATOR | Path ID | `Result<null>` | 规划 |
| POST | `/control/{id}/cancel` | ADMIN/OPERATOR | Path ID | `Result<null>` | 规划 |

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

状态历史的存储边界已经冻结：Redis List 仍用于最近高频状态（最多2880条、24小时过期），
MySQL 表 `farm_printer_status_history` 保存首次样本、状态变化样本和每分钟采样样本，支持服务重启后分页查询。
新增数据库卷会自动执行 `06-add-printer-status-history.sql`；已有 Docker 数据卷不会自动执行，升级前备份后手工执行该脚本。

### 5.2 任务

| 方法 | 目标地址 | 权限 | 请求 | 返回 | 状态 |
|---|---|---|---|---|---|
| POST | `/print-jobs` | ADMIN/OPERATOR | `fileId,priority,printerId?` | 新任务 ID | 规划，作为标准创建地址 |
| POST | `/print-jobs/{id}/retry` | ADMIN/OPERATOR | Path ID | `Result<null>` | 规划 |
| POST | `/print-jobs/{id}/requeue` | ADMIN/OPERATOR | Path ID | `Result<null>` | 规划 |
| PUT | `/print-jobs/{id}/priority` | ADMIN/OPERATOR | `priority` | `Result<null>` | 规划 |

现有 `/print-jobs/create` 保留为兼容地址，前端新代码统一使用 `POST /print-jobs`。

### 5.3 文件

| 方法 | 目标地址 | 权限 | 请求 | 返回 | 状态 |
|---|---|---|---|---|---|
| GET | `/print-files/tree` | ADMIN/OPERATOR | 无或 `parentId` | `FileNodeVO[]` | 规划 |
| GET | `/print-files/{id}/jobs` | ADMIN/OPERATOR | 分页参数 | 任务分页 | 规划 |
| GET | `/print-files/{id}/preview` | ADMIN/OPERATOR | Path ID | 安全预览信息 | 规划 |

文件接口按当前登录用户隔离资源；`OPERATOR` 只能访问本人文件，`ADMIN` 可查看和管理全部文件。管理员分页查询可通过 `userId` 筛选指定用户，不传时查询全部。

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

`apiKey` 只允许出现在新增/修改请求中，任何响应 DTO 都不得返回明文 API Key。

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
设备上报取消时允许 `PRINTING -> CANCELLED`；用户通过当前任务删除接口取消打印中的任务仍返回 HTTP 422。

已有 Docker 数据卷不会自动执行新增 SQL。升级已有数据库前请先备份，然后手动执行：

```bash
mysql -u root -p farm < src/main/resources/db/migration/04-normalize-print-job-status.sql
mysql -u root -p farm < src/main/resources/db/migration/05-normalize-printer-firmware-type.sql
mysql -u root -p farm < src/main/resources/db/migration/06-add-printer-status-history.sql
```

### 6.3 PrintFileVO

```json
{
  "id": 20,
  "parentId": null,
  "folder": false,
  "originalName": "demo.gcode",
  "fileSize": 123456,
  "fileUrl": "https://storage.example/presigned-url",
  "userId": 1,
  "createdAt": "2026-09-02T17:00:00",
  "estimatedSeconds": 3600,
  "materialType": "PLA",
  "nozzleSize": 0.4,
  "filamentWeight": 12.5,
  "filamentLength": 3000,
  "nozzleTemp": 210,
  "bedTemp": 60,
  "layerHeight": 0.2,
  "printCount": 0,
  "successRate": 0.0
}
```

- `fileSize`：Long，单位字节。
- `estimatedSeconds`：Long，单位秒。
- `filamentWeight`：Double，单位克。
- 温度：Double，单位摄氏度。
- `successRate`：Double，范围 0-100，表示百分比。
- `rustfsKey`、内部存储路径和 API Key 不属于前端 DTO。

### 6.4 时间、数字和金额

- REST 的 `LocalDateTime` 使用 ISO-8601 字符串：`yyyy-MM-dd'T'HH:mm:ss`。
- 当前系统默认时区为服务端本地时区，现阶段按 Asia/Shanghai 使用。
- WebSocket `timestamp` 使用 Unix epoch 毫秒。
- ID 使用 Long，前端 JavaScript 建议按字符串安全处理超大 ID。
- 进度使用 Double，范围 0-100。
- 金额字段当前不存在；如果以后增加，使用整数分或 Decimal 字符串，禁止使用 Double 表示金额。

## 7. WebSocket 契约

### 7.1 连接

```text
ws://<server-host>:8080/ws/farm-status
```

规划中的连接方式：

```text
ws://<server-host>:8080/ws/farm-status?token=<JWT>
```

WebSocket 握手必须携带登录接口返回的 JWT。浏览器客户端使用查询参数传递：`/ws/farm-status?token=<JWT>`；缺少、无效或过期 Token 的连接会被服务端拒绝。当前连接仍是农场级广播，后续如需按打印机订阅再增加细粒度隔离。

### 7.2 消息格式

统一使用：

```json
{
  "type": "PRINTER_STATUS",
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

消息类型冻结为：

```text
SNAPSHOT          连接后的全量快照
PRINTER_STATUS    单台打印机状态变化
PRINTER_OFFLINE   打印机离线
JOB_STATUS        任务状态变化
```

当前已冻结消息类型和 `FarmStatusMessage` 顶层结构，并由服务端校验类型、时间戳、关联 ID 和敏感字段。鉴权成功后服务端发送一次 `SNAPSHOT`，其 `data.printers` 使用安全 `PrinterVO`，没有打印机时返回空数组。监控任务通过 `WebSocketEventPublisher` 发布 `PRINTER_STATUS` 和 `PRINTER_OFFLINE`：状态/进度数据变化时推送，连续离线只推送一次，设备恢复后重新推送状态。任务服务和监控任务在任务状态 `updateById` 成功后发布 `JOB_STATUS`；没有绑定打印机的排队任务不发送任务事件。基础生命周期测试已覆盖有效/无效 Token、首帧快照、发送失败清理和断开清理；真实容器级网络测试仍待补充。本阶段已完成握手鉴权，生产环境不再允许匿名广播。

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

`RrfApiClientTest` 已通过可复现 HTTP Mock 测试，覆盖会话、状态/进度、G-code、原始文件上传和密码错误分类。这里的测试证明协议调用边界和解析逻辑，不等同于真实 RRF 3.7 设备验收。`apiKey` 在 RRF 适配中作为设备密码使用，不能当作长期 session key；真实设备仍需确认固件构建、standalone/SBC 模式、`0:/gcodes` 路径可见性、会话限制以及 `M0/M112/M32` 的现场副作用。

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
```

已有数据卷不会因为修改 SQL 自动升级。升级前必须备份，并手工执行经过确认的增量 SQL。

### 9.3 测试环境

```bash
mvn test
```

测试使用 H2 随机端口，关闭定时任务和 WebSocket。当前没有自动生成的测试用户，也没有真实数据库 HTTP 权限测试；已增加文件/任务服务层归属测试，以及未携带 Token 的 WebSocket 握手测试。

### 9.4 真实打印机

开发环境默认关闭：

```yaml
farm.tasks.enabled=false
```

没有真实打印机时不要打开监控任务，否则会持续访问不存在的设备。当前 Moonraker 模拟接口只在 `dev/test` Profile 加载，不代表完整 Klipper 或 RRF 模拟器。

## 10. 当前已知差异和验收条件

当前规划接口的状态：

1. `PENDING/QUEUED` 统一为 `QUEUED`。
2. `CANCELED/CANCELLED` 统一为 `CANCELLED`。
3. `POST /print-jobs` 与旧 `/create` 的兼容策略。
4. 统一分页返回字段。
5. 已修复 `BusinessException` 错误码被丢失的问题。
6. 已统一 HTTP 401、403 和 JSON 错误格式；仍需补充更多端到端错误场景测试。
7. 已增加文件和任务的服务层资源归属校验；打印机仍是农场共享资源。
8. 返回 VO，禁止直接暴露 `apiKey` 和 `rustfsKey`。
9. 文件分页已支持名称、材质筛选。
10. 新建文件夹已正确设置用户归属并校验父目录。
11. WebSocket 已完成握手鉴权；`type`、初始快照、离线消息仍待补充。
12. 为 ADMIN/OPERATOR 增加 401/403 集成测试。
13. Klipper 和 RRF 都通过协议适配器接入；RRF 已有可复现 HTTP 协议测试，尚待真实设备联调。

RRF 3.7 协议证据已登记在 [RRF 3.7 协议证据](.kiro/specs/printer-protocol-and-websocket/rrf-3.7-protocol-evidence.md)。当前实现只覆盖已确认的官方 HTTP/G-code 边界；具体设备响应、存储路径、会话重连和宏副作用仍需真实设备确认，不能把 Mock 测试结果当作实机验收。

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

第二阶段等待后端完成：

```text
打印机详情、历史、统计
恢复和取消控制接口
任务重试、重新排队、调整优先级
文件目录树和任务历史
WebSocket 新消息协议
RRF 3.7 设备接入
```

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

设备协议当前集中在：

```text
src/main/java/com/example/farm/common/utils/MoonrakerApiClient.java
```

该类是 RRF 适配改造的替换点，后续应由 `PrinterProtocolAdapter` 调用，而不是由 Controller 直接调用。

### 12.3 Swagger/OpenAPI

运行服务后访问：

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/v3/api-docs
```

Swagger 反映的是当前 Controller，不会自动包含本文的规划接口。规划接口实现后，必须同步补充 OpenAPI 的请求体、响应体、枚举和 Bearer Token 配置。

仓库中的 `API_DOCUMENT.md` 是历史手工文档，存在匿名注册、任务创建地址和 WebSocket 文件名等过期内容；接口联调以本文和实际 Controller 为准。

### 12.4 测试状态

当前测试包括：

```text
src/test/java/com/example/farm/FarmApplicationTests.java
src/test/java/com/example/farm/service/PrintJobOwnershipTest.java
src/test/java/com/example/farm/service/PrintFileOwnershipTest.java
src/test/java/com/example/farm/controller/WebSocketSecurityTest.java
```

上下文测试使用 `test` Profile，关闭任务和 WebSocket；归属测试使用服务层单元测试，WebSocket 测试覆盖无 Token 拒绝。当前没有：

- Controller HTTP 接口测试；
- ADMIN/OPERATOR 的 401/403 测试；
- 打印机资源归属测试；
- MySQL/RustFS/真实 Redis 联调测试；
- Klipper 或 RRF 设备测试；
- WebSocket 消息格式和断线测试。

因此本文中的“现有接口”表示源码中存在，不表示已经完成真实环境验收。
