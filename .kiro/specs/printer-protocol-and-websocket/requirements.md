# 打印机协议适配与实时状态推送需求规格

版本：v1.0

状态：需求已冻结，后端实现已完成；真实设备与前端联调待验收

依据：

- `API_HANDOFF.md`
- `TODO.md`
- 当前 Java 后端源码

## 1. 背景与目标

Farm 是局域网单农场服务端。协议改造前业务代码直接调用 `MoonrakerApiClient`；当前实现已将协议差异收敛到 Klipper/RRF Adapter，但真实 RRF 3.7 设备行为仍需现场验收。

本需求的目标是建立协议无关的内部设备能力层，使 HTTP API、任务服务、监控任务和 WebSocket 不再依赖具体打印机协议，并为 RRF 3.7 接入提供可测试的边界。

本阶段不改变前端 HTTP API 地址，不引入联网注册、多租户或复杂 RBAC，也不要求在没有真实 RRF 设备时伪造完整硬件行为。

## 2. 当前真实基线

### 2.1 已存在的行为

- `MoonrakerApiClient` 当前提供状态查询、暂停、取消、急停、上传文件和上传后打印能力。
- `PrinterControlService`、`PrintJobService` 和 `PrinterMonitorTask` 通过 `PrinterProtocolAdapterFactory` 选择协议适配器。
- `KlipperMoonrakerAdapter` 封装 Moonraker 调用，`RrfAdapter` 封装 RRF HTTP 会话、状态、G-code 和文件上传边界。
- 业务 Service 负责权限、状态、事务和持久化；Adapter 负责设备通信和统一状态转换。
- 当前 Moonraker 状态来源包含 `webhooks`、`print_stats`、`extruder`、`heater_bed`、`display_status`，已有 Klipper 状态到业务状态的转换逻辑。
- WebSocket 地址为 `/ws/farm-status`，握手需要查询参数 `token` 或 `access_token`，当前最多允许 100 个连接。
- WebSocket 已固定为 `/ws/farm-status`，完成 Token 鉴权、快照、四类业务消息、断线清理和协议级 Ping 保活。
- 开发环境关闭打印机监控任务但开启 WebSocket；没有真实打印机时不应打开设备监控任务。

### 2.2 当前明确缺口

- 尚未完成真实 Klipper/RRF 设备联调，RRF 的具体设备响应、存储路径、会话重连和宏副作用仍需现场确认。
- 尚未完成真实容器级 WebSocket 网络测试；当前测试覆盖消息、生命周期、事件和 Ping 失败清理。
- 前端自动重连、指数退避和告警展示不在当前后端仓库，需要真实前端工程接入。

## 3. 用户角色与边界

### 3.1 ADMIN

- 可以配置和管理所有打印机。
- 可以查看并操作所有任务和设备。
- 可以接收农场级实时状态广播。

### 3.2 OPERATOR

- 可以查看打印机状态。
- 可以按照现有资源归属规则创建、派发和控制允许操作的任务。
- 可以执行暂停、恢复、取消和急停等现场操作，但后端必须继续执行既有权限、资源归属和状态校验。
- 可以接收与 ADMIN 相同的农场级实时状态广播；第一版不做按打印机订阅。

### 3.3 非目标

- 不新增公开设备控制接口以绕过现有鉴权。
- 不把 API Key、JWT、RustFS key 或其他敏感值放入状态消息。
- 不在本阶段实现设备发现、自动配网、远程互联网访问或多农场管理。

## 4. 功能需求

### FR-01：统一协议适配边界

系统必须提供一个协议无关的内部适配器边界，至少覆盖以下能力：

- 查询设备状态
- 暂停打印
- 恢复打印
- 取消打印
- 急停
- 上传文件
- 启动打印

适配器的输入必须包含设备连接所需的打印机信息和必要的调用参数，不能要求 Controller 自己拼接协议 URL。

验收标准：

1. Controller 不再直接注入 `MoonrakerApiClient`。
2. 任务 Service 和监控任务不再根据 `firmwareType` 编写协议分支。
3. 具体协议客户端只在对应 Adapter 内被调用。
4. 适配器接口能明确表达设备离线、超时、协议不支持和调用失败，而不是把所有情况静默转换为普通 `false`。

### FR-02：协议选择与类型规范化

系统必须根据打印机的 `firmwareType` 选择适配器。

支持类型冻结为：

```text
KLIPPER
RRF
```

兼容读取历史值 `Klipper`、`klipper`、`RRF` 等大小写差异，但新写入值统一为大写。未知协议必须在调用前失败，并返回可识别的“不支持协议”业务错误，不得回退到 Klipper。

验收标准：

1. `Klipper` 历史数据能选择 Klipper Adapter。
2. `RRF` 能选择 RRF Adapter。
3. 未知或空协议不会调用任何设备客户端。
4. 协议选择有单元测试覆盖。

### FR-03：Klipper/Moonraker 行为保持兼容

引入 Adapter 后，Klipper 现有业务行为必须保持一致：

- 状态查询继续得到统一打印机状态、任务级状态、进度、文件名和温度信息。
- 暂停、取消、急停继续调用现有 Moonraker 能力。
- 上传文件和启动打印继续支持 `START_PRINT`、`UPLOAD_ONLY` 安全流程。
- 设备调用失败继续转换为项目约定的设备异常，不得把失败写成成功状态。

验收标准：

1. 现有 Klipper/Moonraker 模拟响应可以驱动 Adapter 测试。
2. 任务在设备调用成功后才进入 `PRINTING`。
3. 设备调用失败时任务和打印机不会被错误标记为已开始打印。
4. 真实设备不可用时，开发环境默认任务关闭的行为保持不变。

### FR-04：RRF 3.7 接入边界

系统必须提供 RRF Adapter 的实现边界，并在设计阶段根据 RRF 3.7 官方 API 或真实设备响应确定 HTTP 路径、请求方式、鉴权方式和响应字段。

在真实协议细节确认前：

- 可以实现 RRF Adapter 接口、能力声明、状态映射和 Mock 客户端。
- 不得编造 RRF API 路径并宣称已支持真实打印。
- 对 RRF 尚未支持的操作必须返回统一的“不支持能力”错误。

验收标准：

1. `firmwareType=RRF` 不会误调用 Moonraker。
2. RRF 状态可以映射到系统状态：`OFFLINE`、`IDLE`、`PREPARING`、`PRINTING`、`PAUSED`、`ERROR`、`UNKNOWN`。
3. 未实现或设备不支持的 RRF 操作有明确错误码和消息。
4. 真实 RRF API 路径和字段均有来源记录或真实设备测试证据后，才标记对应能力完成。

### FR-05：统一设备错误行为

Adapter 和上层 Service 必须区分至少以下情况：

- 打印机不存在：HTTP 404、业务码 404
- 打印机离线或无法连接：HTTP 503、业务码 10001 或 5004
- 打印机忙：HTTP 409、业务码 10002
- 当前状态不允许操作：HTTP 422、业务码 422
- 当前协议不支持某能力：使用稳定的业务错误码，并在 API 交接文档中登记

设备异常日志可以记录打印机 ID、协议类型、操作和错误分类，但不能记录 API Key、JWT 或数据库/RustFS 密钥。

验收标准：

1. HTTP 层仍返回统一 `{code,message,data,timestamp}`。
2. 任务状态只在设备操作结果确认后更新。
3. 设备调用超时不会阻塞监控线程或无限重试。
4. 相同类型的错误在控制接口、任务流程和监控任务中语义一致。

### FR-06：WebSocket 连接与权限

WebSocket 必须使用正式地址：

```text
/ws/farm-status
```

连接必须携带登录返回的 JWT，允许查询参数 `token`，并兼容现有的 `access_token` 参数。未携带、无效或过期 Token 的连接必须拒绝；超过连接上限的连接必须拒绝。第一版继续采用农场级广播，不按打印机细分订阅。

验收标准：

1. 未认证连接不能进入广播集合。
2. ADMIN 和 OPERATOR 的有效 Token 都可以连接。
3. 连接关闭或发送失败后，会话和锁资源被清理。
4. 连接上限为可配置值，默认值保持 100。
5. 服务端按 `farm.websocket.heartbeat-interval` 可配置间隔发送 WebSocket 协议级 Ping，默认间隔为 `30s`；发送失败的会话必须关闭并从广播集合清理。协议级 Pong 不进入四种业务消息类型。

### FR-07：WebSocket 消息契约

所有服务端推送必须使用以下顶层结构：

```json
{
  "type": "PRINTER_STATUS",
  "printerId": 403,
  "timestamp": 1756790000000,
  "data": {}
}
```

冻结消息类型：

```text
SNAPSHOT
PRINTER_STATUS
PRINTER_OFFLINE
JOB_STATUS
```

字段约定：

- `type`：必填字符串，只能是冻结的消息类型。
- `printerId`：打印机相关消息必填；全量快照可省略或使用 `data.printers` 承载多台设备，但结构必须在设计文档中固定。
- `timestamp`：必填 Long，Unix epoch 毫秒。
- `data`：必填对象，不放敏感字段。
- 打印机状态使用系统枚举，不直接暴露 Moonraker/RRF 私有状态作为唯一状态。
- 任务状态使用冻结的 8 个任务状态。

验收标准：

1. 新连接鉴权成功后收到一次 `SNAPSHOT`。
2. 单台打印机状态变化发送 `PRINTER_STATUS`。
3. 设备从可用变为不可达时发送 `PRINTER_OFFLINE`，且不会重复高频刷屏。
4. 任务暂停、启动、完成、失败、取消等状态变化发送 `JOB_STATUS`。
5. 消息可以被前端按 `type` 增量处理，不要求收到消息后重新拉取整个列表。

### FR-08：REST 与 WebSocket 状态一致性

REST 返回的 `PrinterVO`、`PrintJobVO` 与 WebSocket 消息中的状态字段必须使用相同的枚举和字段含义。WebSocket 是增量通知，REST 是首次加载和重新同步的权威数据源。

验收标准：

1. 前端首次进入页面可以先调用 REST，再连接 WebSocket。
2. WebSocket 断开时前端可以继续使用最后一次 REST 数据，并能重新同步。
3. 状态推送失败不影响数据库状态更新和后续 REST 查询。
4. WebSocket 断线、重连和快照流程有自动化测试。

## 5. 数据与安全要求

### 5.1 不变更的公共契约

本需求不修改现有公共 HTTP 路径。新增恢复、取消等 REST 接口必须继续使用 `API_HANDOFF.md` 中的 `/api/v1/control/{id}/...` 地址，并经过 ADMIN/OPERATOR 权限校验。

### 5.2 敏感数据

以下字段永远不得出现在 Adapter 返回的状态对象、WebSocket 消息或 REST 响应中：

- 打印机 `apiKey`
- JWT 和登录密码
- RustFS `rustfsKey`
- 数据库、Redis、RustFS 密钥

### 5.3 数据库

本需求阶段不新增数据库表，不删除已有数据，不自动重建 Docker 数据卷。若为状态历史或统计新增持久化模型，必须另行形成迁移需求和可回滚 SQL。

## 6. 测试与验收范围

最低测试集合：

- 协议类型规范化和 Adapter 选择测试
- Klipper 状态解析、状态映射和设备失败测试
- RRF Mock 状态映射和不支持能力测试
- 控制接口不再直接依赖 Moonraker 的结构测试
- WebSocket Token、连接上限、连接清理测试
- WebSocket `SNAPSHOT`、打印机状态、离线和任务状态消息格式测试
- 任务状态与设备调用结果一致性测试
- ADMIN/OPERATOR/匿名调用的权限测试

在没有真实 Klipper/RRF 设备时，测试必须使用 HTTP Mock 或 Stub；Mock 通过不等于真实 RRF 3.7 已完成。真实设备联调需要单独记录设备固件版本、API 响应和测试时间。

## 7. 完成定义

本需求只有在以下条件全部满足时才算完成：

1. 三个业务调用方（控制、任务、监控）均通过统一 Adapter。
2. Klipper 现有能力经过改造后测试通过。
3. RRF 至少完成 Adapter 边界和状态映射；真实调用能力按证据逐项标记。
4. WebSocket 使用冻结消息结构，支持快照、状态变化、离线和任务事件。
5. 核心错误、权限、敏感字段和断线清理均有测试。
6. `API_HANDOFF.md`、`TODO.md` 与实际 Controller、Service、Adapter、WebSocket 行为一致。
7. 每个实现阶段运行 `mvn test`、`git diff --check`，并创建本地 Git 提交。

## 8. 已冻结的设计决策

以下决策已在 `design.md` 和当前实现中固定：

1. Adapter 设备失败使用领域异常；HTTP 层映射为稳定业务错误码。
2. 第一版由 `uploadFile(..., startPrint)` 表达上传/上传并开始，Service 决定任务状态。
3. `SNAPSHOT` 使用 `printerId=null` 的农场级多设备结构。
4. RRF 仅实现已有官方资料和 Mock 测试证明的 HTTP/G-code 边界，未知能力返回不支持错误。
5. 状态/任务 Service 或监控任务产生事件，由 `WebSocketEventPublisher` 统一构造消息。
6. 状态历史和统计属于 P1.1 独立能力，使用增量 SQL 持久化。
