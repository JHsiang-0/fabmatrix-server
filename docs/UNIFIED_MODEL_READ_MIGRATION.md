# 统一状态读取迁移（T7）

日期：2026-11-05。范围：Server、Client、Cloud 的当前设备状态读取与 V3 上报来源；不改变本地生产任务的占用、确认和物理动作规则。

## 已切换的路径

| 路径 | 实现 | 当前来源 |
| --- | --- | --- |
| Server 完整模型接口 | `PrinterModelService`、`PrinterModelVO` | 持久 canonical 模型及 `CanonicalPrinterSummary` |
| Server 列表/详情/监控配置 | `PrinterStateSummaryService`、`PrinterController`、`PrinterDetailService` | 批量读取 canonical；无有效模型时提供不可用摘要 |
| Server 实时快照/消息 | `FarmStatusSnapshotService`、`WebSocketEventPublisher` | 相同摘要；设备数值不变时，每 15 秒补发有效采样 |
| Client 列表、看板、实时 Store | `printerStateSummary.js`、`stateClock.js`、`realtimeStore.js` 及页面 | 优先 `stateSummary`；按版本/设备 ID/时间校验，不以旧字段补回过期值 |
| Cloud 列表、详情、农场汇总 | `CanonicalPrinterSummaryProjection`、`TelemetryServiceImpl` | 已有完整观测的设备从 canonical 派生，平铺字段与摘要一致 |
| Server 协商到 V3 后的兼容快照 | `CloudSnapshotSource.captureCanonicalSnapshot`、`CloudGatewayAgent` | canonical 派生的状态/进度/主通道温度；完整模型仍由 `printer-state` 发布 |

摘要的 `modelVersion=2` 与完整模型一致，不改变 `/api/v1` 路径或 MQTT 传输版本。它是完整模型的只读投影，不能替代资产描述、多通道观测、执行结果和历史接口。

## 一致性规则

- 当前观测和每项测量最多 30 秒，允许 5 秒未来偏差；温度等数值仅采用 GOOD 质量，真实零值保留。离线、停监、采集不一致或过期时不显示当前测量和当前执行摘要。
- 温度按当前工具选择，其次配置的主工具；热床按配置主通道。没有明确选择时，仅接受唯一的同类通道，多通道不猜测。
- Client 根据服务端时间加本地接收后的间隔判断新鲜度，每秒重算；显示或合并旧字段不刷新接收时间。HTTP 与 WebSocket 交错时保留 `serverTime` 较新的摘要。
- 本地读取校验设备 ID/UID，Cloud 保留组织/农场授权、网关绑定、epoch、描述版本和设备身份边界。
- 设备物理状态和本地任务占用是不同事实。`Printer.status`、`currentJobId`、任务状态和安全确认继续由本地执行流程决定，不能因摘要显示空闲就绕过后端检查。

## 保留的兼容边界

- `MoonrakerStatusDTO`、`PrinterDeviceStatus` 及旧缓存仍用于协议边界、本地任务核验、生产控制和既有历史记录；旧类型未全部删除。
- Server 的旧平铺 API/WebSocket 字段保留，供旧 Client 升级过渡；新 Client 仅在没有 `stateSummary` 字段的旧响应中使用原读取路径。空摘要或无效摘要不触发旧值兜底。
- 未协商到 V3 的 V1/V2 上报仍保留。V3 的快照/心跳用于离线、停监与网关健康传播，不能直接删除；当前完整状态发布只接受新鲜在线观测。
- Cloud 仅旧协议设备继续走旧投影；已有完整观测的设备失去新鲜度或绑定有效性后，不回退到冲突的旧快照。

后续若迁移任务核验、历史写入或移除旧协议，需要分别约定业务状态/设备动作证据、历史口径及升级窗口，并独立回归；不应通过删除旧 DTO 强行完成。

## 验证和提交

| 仓库 | 本轮验证 | 提交 |
| --- | --- | --- |
| Server | 全量 902 项测试、JAR 打包 | `33999a6`、`f0c2572`、`30e4176` |
| Client | 最新 457 项 Node 测试、lint、正式/Mock/桌面构建、2 项 Electron dist、发布扫描；首次 39 项浏览器通过/4 项功能跳过，交错修复后 2 项相关页面回归 | `db2edb3`、`ddcf2a5` |
| Cloud | `mvnw.cmd verify` 81 项测试；使用本次 Server JAR 的隔离 MySQL/EMQX TLS/SQLite/代理链路 37 项检查 | `f5aac58` |

隔离检查包含模拟设备的增删、200 台库存、身份冲突、重启/重连、重绑和吊销，未发布宿主端口或创建持久卷，测试资源已清理。没有连接真实打印机、操作生产任务、修改真实数据库或升级常驻/安装版服务；Windows 安装包也未重新构建。这些测试不能代替现场升级后的只读验收。
