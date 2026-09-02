# Farm v1 全项目技术设计

版本：v1.0

状态：设计已冻结，后端 P0/P1 已实现；前端与真实环境待验收

需求来源：[requirements.md](./requirements.md)

## 1. 总体架构

```text
前端浏览器/客户端
    ├── REST /api/v1
    └── WebSocket /ws/farm-status
            ↓
Controller / WebSocket Endpoint
            ↓
Application Service
    ├── 权限、资源归属、状态机、事务
    ├── Mapper -> MySQL
    ├── Redis 缓存、锁、登录保护
    ├── RustFsClient -> RustFS
    └── PrinterProtocolAdapterFactory
            ↓
    ├── KlipperMoonrakerAdapter -> MoonrakerApiClient
    └── RrfAdapter              -> RrfApiClient
```

当前代码中的主要改造点：

- `PrinterControlController` 通过控制 Service 调用协议 Adapter。
- `PrintJobServiceImpl` 通过 Factory 获取 Adapter，负责安全打印、任务状态和事务。
- `PrinterMonitorTask` 通过 Adapter 获取统一状态，再同步缓存、数据库和 WebSocket 事件。
- `WebSocketServer` 已完成鉴权、统一事件对象、快照、协议级 Ping 保活和失败清理。

## 2. 包和类规划

建议新增以下包，具体命名以现有项目风格为准：

```text
com.example.farm.protocol
├── PrinterProtocolAdapter
├── PrinterProtocolType
├── PrinterProtocolAdapterFactory
├── PrinterEndpoint
├── PrinterDeviceStatus
├── PrinterOperation
├── FailureCategory
├── PrinterProtocolException
├── KlipperMoonrakerAdapter
├── RrfAdapter
└── client
    └── RrfApiClient

com.example.farm.service
├── PrinterControlService
├── PrinterStatusService
└── WebSocketEventPublisher

com.example.farm.entity.websocket
└── FarmStatusMessage
```

不强制新建所有类：若现有 Service 已承担同一职责，可在不扩大耦合的前提下复用；但具体协议客户端不得回流到 Controller。

## 3. 统一领域对象

### 3.1 协议类型

`PrinterProtocolType.normalize(String)` 负责忽略大小写、去除空格、兼容历史 `Klipper`，并拒绝空值和未知值。写库前使用大写枚举名。

### 3.2 设备端点

```java
public record PrinterEndpoint(
        Long printerId,
        String ipAddress,
        String apiKey,
        PrinterProtocolType protocolType
) {}
```

该对象只在服务端内部传递，不作为 Jackson 响应对象。

### 3.3 统一设备状态

```java
public record PrinterDeviceStatus(
        PrinterStatus status,
        String rawState,
        String systemMessage,
        String filename,
        BigDecimal progress,
        BigDecimal toolTemperature,
        BigDecimal toolTarget,
        BigDecimal bedTemperature,
        BigDecimal bedTarget,
        BigDecimal printDuration,
        BigDecimal totalDuration,
        BigDecimal filamentUsed
) {}
```

对外状态只允许：`OFFLINE`、`IDLE`、`PREPARING`、`PRINTING`、`PAUSED`、`ERROR`、`UNKNOWN`。协议私有状态放到 `rawState`，不能让前端依赖它。

### 3.4 设备异常

使用 `PrinterProtocolException` 表达协议层失败，至少带有操作、协议和失败分类。分类包括 `OFFLINE`、`TIMEOUT`、`UNSUPPORTED`、`REJECTED`、`PROTOCOL_ERROR`、`UNKNOWN`。

全局异常映射：

| 分类 | HTTP | 业务码 |
|---|---:|---:|
| 设备离线 | 503 | 10001 |
| 网络/超时 | 503 | 5004 |
| 打印机忙 | 409 | 10002 |
| 状态不允许 | 422 | 422 |
| 协议不支持 | 422 | 10003 |

协议层异常原文只用于内部日志，返回给客户端的消息使用安全、稳定的业务文本。

## 4. Adapter 设计

```java
public interface PrinterProtocolAdapter {
    PrinterProtocolType protocolType();
    PrinterDeviceStatus getStatus(PrinterEndpoint endpoint);
    void pause(PrinterEndpoint endpoint);
    void resume(PrinterEndpoint endpoint);
    void cancel(PrinterEndpoint endpoint);
    void emergencyStop(PrinterEndpoint endpoint);
    void uploadFile(PrinterEndpoint endpoint,
                    Resource file,
                    String filename,
                    boolean startPrint);
}
```

`PrinterProtocolAdapterFactory` 注入所有实现，根据规范化后的协议返回唯一 Adapter。未知协议不得回退。

### 4.1 Klipper

`KlipperMoonrakerAdapter` 是 `MoonrakerApiClient` 的唯一调用者：

- 将现有 `MoonrakerStatusDTO` 转换为 `PrinterDeviceStatus`。
- 保留当前 `webhooks` 优先、`print_stats` 次之的状态解析逻辑。
- 将当前的 `null/false` 设备失败转换为明确异常。
- 补充恢复打印操作。
- 保留 API Key 只进入请求头，不进入日志和响应。

### 4.2 RRF

`RrfAdapter` 只依赖 `RrfApiClient`。RRF 3.7 的 URL、请求方法、鉴权和字段必须在官方文档或真实设备响应确认后实现。确认前只实现协议选择、状态映射、Mock 和未支持能力异常。

## 5. Service 改造

### 5.1 PrinterControlService

控制 Service 执行：查询打印机、资源/角色校验、状态校验、构造端点、Factory 选择、Adapter 调用、缓存/数据库更新和事件发布。Controller 只负责参数接收与统一响应。

### 5.2 PrintJobService

保留现有任务归属、安全确认和状态机，替换以下具体调用：

- 派发/上传并打印
- `START_PRINT` 或 `UPLOAD_ONLY`
- 取消、暂停、恢复
- 后续重试、重新排队

数据库状态更新必须发生在设备调用成功之后；成功事件在事务成功后发布。自动派发由 `PrintJobService.assignQueuedJob` 执行，调度器本身只负责分布式锁和扫描；该 Service 事务内重新校验 `QUEUED`/`IDLE`，依次保存任务和打印机，任一保存失败抛出业务异常并回滚，之后才发布任务事件。

### 5.3 PrinterMonitorTask

每台打印机独立获取 Adapter 并查询状态；一台设备异常只影响该设备。监控服务负责把统一状态写入缓存/数据库、同步关联任务，并通过事件发布器推送 WebSocket。

## 6. WebSocket 设计

### 6.1 连接

保留 `WebSocketServer` 的 `/ws/farm-status` 和 `token/access_token` 查询参数兼容逻辑。连接上限从配置读取，默认 100。Endpoint 只负责鉴权、Session 注册、发送和资源清理。

### 6.2 消息

```java
public record FarmStatusMessage(
        String type,
        Long printerId,
        long timestamp,
        Object data
) {}
```

`WebSocketEventPublisher` 只允许发布四种消息：

- `SNAPSHOT`：`data.printers` 为安全的打印机状态列表。
- `PRINTER_STATUS`：单台打印机当前状态、进度和温度。
- `PRINTER_OFFLINE`：单台设备离线和安全原因。
- `JOB_STATUS`：任务 ID、任务状态、进度和错误原因。

所有消息必须包含 `type`、`timestamp`、`data`；打印机相关消息必须包含 `printerId`。消息数据不直接使用 Entity。

### 6.3 事件来源

- `FarmStatusSnapshotService` 为新连接构建全量快照。
- `PrinterMonitorTask` 在状态或可用性发生变化时发布打印机事件。
- `PrintJobService` 在任务状态持久化成功后发布任务事件。
- WebSocket 发送失败只清理会话，不回滚业务操作。

由于 `@ServerEndpoint` 的实例化方式，Endpoint 不自行创建 Mapper/Service；通过 Spring 桥接组件或明确的应用级发布器注入依赖。

## 7. P1 接口设计原则

P1 新接口先在 API_HANDOFF 中冻结，再实现 Controller/Service：

- 打印机详情使用 `PrinterDetailVO`，历史和统计使用独立 DTO。
- 文件目录树使用 `FileNodeVO`，预览不返回内部存储 key。
- 文件缩略图不在公共 VO 中返回 RustFS 直连地址；通过 `GET /print-files/{id}/thumbnail` 按需签发短期 URL，删除文件时同步清理缩略图对象。
- 任务标准创建地址为 `POST /api/v1/print-jobs`，保留旧 `/create` 兼容。
- 任务重试、重新排队和优先级修改复用现有状态机和归属校验。
- 用户增加 `/auth/me`，用户分页和资料响应继续脱敏。

## 8. 数据库和迁移

协议适配和 WebSocket 第一阶段不新增表。若实现状态历史、统计或操作审计需要新增表：

1. 先补充需求和设计。
2. 提供增量、可重复执行或明确一次性执行的 SQL。
3. 说明已有 Docker 数据卷的备份、执行和回滚步骤。
4. 不假设历史 `V*.sql` 会被 Spring 自动执行。

## 9. 测试策略

| 层级 | 重点 |
|---|---|
| 单元 | 协议规范化、Factory、状态映射、异常分类、消息序列化 |
| Service | 权限、资源归属、状态机、设备成功/失败后的状态一致性 |
| Controller | 401/403、参数校验、错误码、响应结构 |
| WebSocket | Token、连接上限、快照、四类消息、断线清理 |
| Mapper | MySQL 关键查询、筛选和分页 |
| 集成 | Mock Klipper/RRF、RustFS、Redis、完整任务流程 |
| 端到端 | 上传文件 → 创建任务 → 派发 → 安全确认 → 启动 → 完成 |

开发环境没有真实设备时，使用 Mock/Stub；真实 RRF 联调必须单独记录证据。

## 10. 每个 Task 的验收闸门

实现前：确认任务范围、目标文件和不影响的用户修改。

实现后：

```bash
git diff --check
mvn test
```

涉及启动、HTTP 或设备调用时，再执行针对性的启动/接口/Mock 验证。通过后更新 `tasks.md`、`TODO.md`、`API_HANDOFF.md`，然后创建一个本地 Git 提交。
