# 打印机协议适配与实时状态推送技术设计

版本：v1.0

状态：设计已冻结，后端实现已完成；真实设备与前端联调待验收

对应需求：`.kiro/specs/printer-protocol-and-websocket/requirements.md`

## 1. 设计原则

1. 公共 HTTP API 不感知 Klipper 或 RRF 的私有协议。
2. 设备协议差异只存在于 Adapter 和对应的协议客户端中。
3. Service 负责业务状态、权限、事务和数据库；Adapter 只负责设备通信和协议转换。
4. 设备失败必须显式表达，不能把 `null`、`false` 和异常混用到业务层无法区分。
5. WebSocket 只负责连接管理和事件发送；事件由状态/任务业务层产生。
6. 没有真实 RRF 设备和权威协议资料时，不写死 RRF 的 HTTP 路径。

## 2. 目标调用链

```text
HTTP Controller
    -> PrinterControlService / PrintJobService
        -> PrinterProtocolAdapterFactory
            -> PrinterProtocolAdapter
                -> KlipperMoonrakerAdapter -> MoonrakerApiClient
                -> RrfAdapter              -> RrfApiClient

PrinterMonitorTask
    -> PrinterProtocolAdapterFactory
        -> PrinterProtocolAdapter.getStatus()
    -> PrinterStatusService/现有缓存与任务同步逻辑
    -> WebSocketEventPublisher
        -> WebSocketServer
```

Controller 不再持有 `MoonrakerApiClient`。`PrintJobServiceImpl` 和 `PrinterMonitorTask` 也不直接注入具体协议客户端。

## 3. 领域模型

### 3.1 协议类型

新增内部枚举或等价的规范化组件：

```java
public enum PrinterProtocolType {
    KLIPPER,
    RRF;

    public static PrinterProtocolType normalize(String value);
}
```

`normalize` 去除首尾空格并忽略大小写；历史值 `Klipper` 转为 `KLIPPER`。空值和未知值抛出“不支持协议”异常。写入 `farm_printer.firmware_type` 前统一使用枚举名称。

### 3.2 设备连接配置

Adapter 接收一个内部设备配置对象，不直接接收 HTTP 请求 DTO：

```java
public record PrinterEndpoint(
        Long printerId,
        String ipAddress,
        String apiKey,
        PrinterProtocolType protocolType
) {}
```

该对象只在服务端内存中使用。禁止日志输出 `apiKey`；禁止把该对象序列化到 REST 或 WebSocket。

### 3.3 统一设备状态

新增协议无关状态对象，例如：

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

其中 `PrinterStatus` 只允许：

```text
OFFLINE, IDLE, PREPARING, PRINTING, PAUSED, ERROR, UNKNOWN
```

`rawState` 只用于诊断和映射测试，不作为前端业务状态。状态对象不包含任何凭据。

### 3.4 设备操作结果和异常

设备操作采用“成功返回、失败抛出领域异常”的方式，不返回裸 `boolean`：

```java
public enum PrinterOperation {
    GET_STATUS, PAUSE, RESUME, CANCEL, EMERGENCY_STOP,
    UPLOAD_FILE, START_PRINT
}
```

建议新增 `PrinterProtocolException`，至少包含：

- `PrinterOperation operation`
- `PrinterProtocolType protocolType`
- `FailureCategory category`
- 安全的用户可见 message
- 原始异常作为 cause，但不直接返回原始异常文本

`FailureCategory` 至少包含：`OFFLINE`、`TIMEOUT`、`UNSUPPORTED`、`REJECTED`、`PROTOCOL_ERROR`、`UNKNOWN`。

Controller/全局异常处理器将其映射为现有业务错误码：离线优先使用 `10001`，网络超时使用 `5004`，协议不支持使用稳定业务码 `10003`；非法业务状态仍使用 `422`，打印机忙使用 `10002`。

## 4. Adapter 接口

接口放在独立协议包中，例如 `com.example.farm.protocol.PrinterProtocolAdapter`：

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

`uploadFile(..., true)` 表示协议层完成“上传并开始”；`false` 表示只上传。是否允许当前任务进入 `PRINTING`、`READY` 由 `PrintJobService` 根据调用成功和任务状态决定。

`startPrint` 的独立语义在当前代码中与上传参数绑定，因此第一版不额外复制一个协议方法；如果 RRF 证明上传和开始必须分成两个调用，再在设计变更中增加独立 `startPrint` 能力，不在 Controller 中临时拼装。

## 5. Adapter 选择

新增 `PrinterProtocolAdapterFactory`，通过 Spring 注入所有 `PrinterProtocolAdapter`：

```java
public PrinterProtocolAdapter getAdapter(String firmwareType) {
    PrinterProtocolType type = PrinterProtocolType.normalize(firmwareType);
    return adapters.stream()
            .filter(adapter -> adapter.protocolType() == type)
            .findFirst()
            .orElseThrow(() -> unsupported(type));
}
```

设计约束：

- 不允许未知类型回退到 Klipper。
- 同一协议只能有一个生产 Adapter；重复注册在启动时失败或明确报警。
- Factory 的单元测试覆盖历史值、大小写、空值和未知值。

## 6. Klipper Adapter 改造

`KlipperMoonrakerAdapter` 是 `MoonrakerApiClient` 的唯一调用方。

### 6.1 状态查询

- 调用现有 Moonraker 状态查询。
- 将 `MoonrakerStatusDTO` 转换为 `PrinterDeviceStatus`。
- 明确 `webhooks` 系统状态优先级和 `print_stats` 任务状态映射。
- `startup` 映射为 `PREPARING` 或项目确认的离线语义；最终值必须与 `PrinterStatus` 枚举一致。
- HTTP 超时、连接拒绝、空响应统一转为 `OFFLINE/TIMEOUT` 异常或离线状态，不能让上层通过任意 `null` 猜测原因。

### 6.2 控制与文件

- 暂停、取消、急停复用现有 Moonraker URL。
- 恢复打印需要在 Moonraker 客户端增加明确的恢复调用；路径和响应按现有 Moonraker 约定实现并增加测试。
- 上传文件由 Adapter 传入 `apiKey`，由协议客户端设置请求头；日志只保留 printerId、操作和文件名。
- Adapter 不修改数据库状态。

## 7. RRF Adapter 设计

新增 `RrfAdapter` 和独立 `RrfApiClient`。RRF 3.7 的 URL、认证头、状态字段和上传流程必须从官方文档或真实设备响应确认，并记录在 `API_HANDOFF.md` 的协议附录中。当前已按官方资料实现已确认的 HTTP 调用，并用可复现 Mock 验证；真实设备差异仍单独保留为联调事项。

在真实设备联调前已完成：

- `protocolType() == RRF`。
- 状态响应解析器与 Mock 响应。
- RRF 私有状态到 `PrinterStatus` 的纯函数映射。
- 对未确认字段按 `null/UNKNOWN` 处理，对协议失败按统一 `PrinterProtocolException` 分类。

禁止：

- 在 `MoonrakerApiClient` 中增加 `if (firmwareType.equals("RRF"))`。
- 将 RRF URL 替换为 Moonraker URL。
- 将 Mock 通过当成真实 RRF 打印成功。

## 8. Service 改造边界

### 8.1 打印机控制

新增或改造 `PrinterControlService`：

1. 根据 ID 查询打印机。
2. 执行现有权限和状态校验。
3. 构造 `PrinterEndpoint`。
4. 从 Factory 获取 Adapter。
5. 调用设备操作。
6. 根据成功结果更新数据库/缓存并发布事件。

`PrinterControlController` 只接收 ID、调用 Service、返回 `Result<null>`。

### 8.2 打印任务

`PrintJobServiceImpl` 保留任务状态机、文件归属、安全确认和事务边界，但把以下直接调用替换为 Adapter：

- 任务派发/上传并打印
- 安全流程中的上传或启动
- 任务取消
- 后续重试和重新排队

任务只有在 Adapter 成功返回后才能进入 `PRINTING`；Adapter 失败时保持或回滚到正确的业务状态。

### 8.3 监控任务

`PrinterMonitorTask` 只调用 Factory 得到的 Adapter 查询状态。状态转换、Redis 缓存、任务同步和 WebSocket 事件发布仍由应用层负责。

监控任务必须对单台设备异常隔离：一台设备超时不能取消同一轮其他设备的查询，也不能输出完整异常中的敏感信息。

## 9. WebSocket 事件设计

### 9.1 消息对象

新增不可变的消息对象：

```java
public record FarmStatusMessage(
        String type,
        Long printerId,
        long timestamp,
        Object data
) {}
```

生产代码不直接向 `WebSocketServer.broadcastPrinterStatus` 传任意 Map；当前打印机状态已使用 `FarmStatusMessage`，后续由 `WebSocketEventPublisher` 统一创建快照、离线和任务消息。

### 9.2 消息类型和数据

#### SNAPSHOT

```json
{
  "type": "SNAPSHOT",
  "printerId": null,
  "timestamp": 1756790000000,
  "data": {
    "printers": []
  }
}
```

`data.printers` 使用安全的 `PrinterVO` 或专用状态快照 VO，不含 `apiKey`。新连接鉴权成功后只发送一次；没有打印机时仍发送 `printers: []`。

#### PRINTER_STATUS

```json
{
  "type": "PRINTER_STATUS",
  "printerId": 403,
  "timestamp": 1756790000000,
  "data": {
    "status": "PRINTING",
    "progress": 35.5,
    "filename": "demo.gcode",
    "toolTemperature": 210.0,
    "bedTemperature": 60.0
  }
}
```

#### PRINTER_OFFLINE

```json
{
  "type": "PRINTER_OFFLINE",
  "printerId": 403,
  "timestamp": 1756790000000,
  "data": {
    "status": "OFFLINE",
    "reason": "设备无法连接"
  }
}
```

#### JOB_STATUS

```json
{
  "type": "JOB_STATUS",
  "printerId": 403,
  "timestamp": 1756790000000,
  "data": {
    "jobId": 1001,
    "status": "COMPLETED",
    "progress": 100.0,
    "errorReason": null
  }
}
```

### 9.3 事件发布时机

- `PrinterMonitorTask` 通过 `WebSocketEventPublisher` 比较本轮和上轮设备可用性/业务状态，只在状态或进度数据变化时发布状态事件；设备从在线转离线时发布一次离线事件，连续离线不重复发送。
- 任务 Service 和设备监控任务在任务状态成功持久化后发布 `JOB_STATUS`，避免先通知前端后事务回滚；没有绑定打印机的排队任务不发送设备关联事件。
- WebSocket 推送失败只清理异常连接，不回滚业务事务。
- 快照由独立的 `FarmStatusSnapshotService` 从数据库/缓存构建，不能依赖某一个设备当前在线。

## 10. WebSocket 生命周期

保留 `WebSocketServer` 的 Jakarta Endpoint 入口和当前 JWT 查询参数兼容逻辑，但将以下内容集中管理：

- 连接鉴权
- 可配置连接上限
- Session 资源清理
- 单连接同步发送
- 快照发送
- 协议级 Ping 保活；发送失败复用异常会话清理逻辑

由于 `@ServerEndpoint` 的实例化方式与 Spring Bean 不同，快照查询和事件发布通过明确的 Spring 桥接组件注入或静态安全入口接入；不得在 Endpoint 中自行创建 Service、Mapper 或数据库连接。

## 11. 配置设计

建议新增配置：

```yaml
farm:
  protocol:
    websocket-max-connections: 100
  websocket:
    enabled: true
    heartbeat-interval: 30s
```

已有 `farm.tasks.enabled=false` 的开发默认值保持不变。适配器本身不因没有真实设备而自动启动轮询。

## 12. 测试设计

### 单元测试

- `PrinterProtocolTypeTest`
- `PrinterProtocolAdapterFactoryTest`
- `KlipperMoonrakerAdapterTest`
- `RrfAdapterTest`
- 统一状态映射测试
- 设备异常分类测试
- `FarmStatusMessage` 序列化测试

### Service 测试

- 控制 Service 选择正确 Adapter。
- 设备失败时不更新为 `PRINTING`。
- 任务取消、暂停、恢复仍执行归属和状态校验。
- 事务成功后发布任务事件，异常时不发布成功事件。

### WebSocket 测试

- 无 Token、无效 Token、过期 Token拒绝。
- ADMIN/OPERATOR 有效 Token 接入。
- 连接上限拒绝。
- 新连接收到一个 `SNAPSHOT`。
- 四类消息 JSON 字段和状态枚举正确。
- 发送失败清理 Session 和锁。

没有真实设备时使用 Mock HTTP 响应；真实设备测试另行记录，不把 Mock 结果写成真实协议支持结论。

## 13. 分阶段实施顺序

1. 先落地领域枚举、状态对象、异常和 Factory。
2. 将 Moonraker 调用封装进 Klipper Adapter，并保持旧行为。
3. 改造控制、任务和监控调用链。
4. 增加恢复操作及 Klipper Mock 测试。
5. 根据官方证据实现 RRF Adapter 和 HTTP 客户端，并保留真实设备联调边界。
6. 增加 WebSocket 消息对象、事件发布器和快照。
7. 补齐 WebSocket 事件、离线去重和端到端测试。
8. 更新 `API_HANDOFF.md`、`TODO.md`，运行测试并提交。

## 14. 设计决策记录

- 选择异常表达设备失败：避免当前 `null/false/Exception` 三套语义继续扩散。
- 保留上传时的 `startPrint` 参数：与当前 Klipper 客户端和 `START_PRINT/UPLOAD_ONLY` 业务契约兼容。
- WebSocket 快照使用 `data.printers`：一次消息表达农场全量状态，前端连接后无需等待多条不可区分的初始化消息。
- 第一版继续农场级广播：符合本地单农场目标，减少订阅权限复杂度。
- RRF API 路径延后确认：协议事实必须来自官方资料或真实设备，不能由接口设计猜测。
