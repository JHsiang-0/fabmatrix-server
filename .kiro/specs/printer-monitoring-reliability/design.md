# 打印机监控可靠性技术设计

状态：已实现，待真实设备联调

## 1. 设计原则

1. 数据库中的打印机资产不等于当前需要轮询的设备。
2. `connectionStatus` 表示通信健康，`status` 表示设备业务状态。
3. API 失败不能推导任务失败；设备空闲不能在没有证据时推导任务完成。
4. 任务绑定以数据库为事实来源，Redis 只做锁和缓存加速。
5. 协议差异只存在于 Klipper/RRF Adapter 和客户端中。
6. 监控负责观测和同步，不负责创建未确认任务或自动派单。

## 2. 目标调用链

```text
Monitor REST Controller -> Monitor Service -> PrinterMonitorProperties/数据库
                                      -> PrinterMonitorTask
                                          -> PrinterProtocolAdapterFactory
                                              -> KlipperMoonrakerAdapter
                                              -> RrfAdapter
                                          -> 状态健康持久化
                                          -> 任务恢复/核对
                                          -> WebSocketEventPublisher
```

## 3. 数据模型

在 `farm_printer` 增加以下字段，具体类型以当前 MySQL/SQLite 兼容性为准：

```text
monitor_enabled              是否纳入监控
connection_status            ONLINE/OFFLINE/UNKNOWN
last_seen_at                 最近成功通信时间
last_communication_error     脱敏后的最近错误摘要
consecutive_failure_count    连续失败次数
last_known_device_status     最近一次设备业务状态
last_protocol_state          最近一次协议原始状态
last_device_uptime           设备运行时间，协议支持时保存
```

`status` 保留为 Farm 设备业务状态。API 失败时不覆盖该字段；成功观测到设备状态时才更新它。

历史数据迁移采用安全默认值：旧记录默认不纳入监控，由 ADMIN 在前端批量启用。新增打印机通过 Service 默认启用。迁移脚本只新增字段和索引，不删除数据。

## 4. 监控目标选择

数据库查询条件：

```text
monitor_enabled = true
ip_address is not null
ip_address <> ''
firmware_type in ('KLIPPER', 'RRF')
```

`farm.monitor.enabled` 仍是总开关；`interval` 和 `concurrency` 仍由配置控制。`printer-ids` 进入兼容期后只允许作为显式临时限制，不能把空值解释成全量历史扫描，并在启动日志中提示迁移状态。

## 5. REST 契约

建议新增：

```text
GET /api/v1/printers/{id}/monitor
PUT /api/v1/printers/{id}/monitor
PUT /api/v1/printers/monitor/batch
```

请求体：

```json
{"enabled": true}
```

批量请求：

```json
{"printerIds": [1, 2, 3], "enabled": true}
```

`PrinterVO`/详情 VO 增加监控开关、通信状态、最近在线时间、失败次数和最近错误摘要；不得返回 API Key。

任务人工核对沿用现有状态机，必要时新增：

```text
POST /api/v1/print-jobs/{jobId}/reconcile
```

Service 必须校验当前用户、任务归属、任务状态、打印机绑定和目标终态。人工核对不调用设备控制接口。

## 6. 状态和失败策略

建议默认阈值：

```text
失败 1~2 次：记录健康信息，保持连接状态
失败达到 3 次：connectionStatus=OFFLINE
成功达到 2 次：connectionStatus=ONLINE
```

阈值应配置化但有安全上限。状态事件按连接状态和业务状态变化去重，失败日志限频。

断网期间：

- 清理实时缓存可以继续，但不能清除 `currentJobId`；
- 保留 `lastKnownDeviceStatus`；
- 活动任务保持原中间状态；
- 设备不参与自动派单；
- 控制接口返回通信失败，不能伪造设备已暂停/取消/急停。

恢复期间：

- 先校验协议、文件名和任务绑定；
- `printing`/`paused` 同步业务状态和进度；
- 明确 `complete` 或文件完成证据才完成任务；
- `idle/standby/ready` 无证据时进入 `RECONCILING`；
- 保留绑定直到人工处理或明确终态成功持久化。

## 7. 协议设计

### 7.1 Klipper/Moonraker

继续使用 `KlipperMoonrakerAdapter`。状态查询应验证 `webhooks` 和 `print_stats` 是否存在；`webhooks.state` 非 `ready` 时表达系统不可用、启动或故障，`print_stats.state` 只在系统 ready 时参与业务状态判断。

`print_stats.filename` 必须参与 Farm 任务文件匹配。`complete` 是有效完成证据；仅 `standby` 不足以完成活动任务。缺少 `[virtual_sdcard]` 或必要对象时返回协议配置错误。

### 7.2 RRF

修复 `RrfApiClient` 的嵌套字段解析：

```text
job.timesLeft.file
job.lastFileName
job.lastDuration
job.lastFileCancelled
job.lastFileAborted
state.upTime
```

RRF Standalone 可能不提供 DSF/DWC 维护的历史字段，因此字段缺失时必须保守进入 `RECONCILING`，不能以 `idle` 推导完成。

## 8. 文件和任务匹配

任务创建/启动时保存设备侧文件标识快照：

```text
farm file id
filename/path
file size（设备协议能提供时）
startedAt
last observed progress
```

文件名不一致时停止进度同步并记录 `RECONCILING` 原因。后续如协议支持稳定文件哈希，再增加哈希校验；第一版不把文件名匹配失败自动判为失败。

## 9. 轮询和多实例

第一阶段保留定时轮询，避免把设备主动事件作为唯一事实来源。根据设备状态选择轮询间隔，并对离线设备退避。多实例使用 Redis 设备级锁或确定性分片，避免重复访问和重复广播。

监控摘要至少包括：总目标数、跳过数、在线数、离线数、协议错误数、平均耗时、慢设备数。

## 10. 验证和发布

数据库升级前必须备份已有 Docker 数据卷并手工执行增量 SQL。测试环境关闭真实设备轮询。真实 Klipper/RRF 的断网、重启和物理打印结果属于集成验收，不由单元测试替代。

## 11. 外部任务观测模型

设备轮询状态增加独立于 `farm_print_job` 的任务来源：

```text
FARM      设备正在执行且文件名明确匹配当前平台任务
EXTERNAL  设备正在执行，但没有平台任务或文件名明确不匹配
UNKNOWN   设备正在执行，但协议没有提供足以匹配的文件名
NONE      设备当前未执行打印
```

`farm_printer.current_job_id` 继续表达平台任务的占用/等待关系，不代表设备当前打印文件必然属于该任务。`READY` 任务遇到 `EXTERNAL` 或 `UNKNOWN` 观测时保持原状态，打印机业务状态仍按设备实际状态显示为 `PRINTING`。已经进入 `PRINTING/PAUSED` 的平台任务若明确观测到其他文件，任务进入 `RECONCILING`，但设备仍按外部打印继续监控。

实时缓存、WebSocket 和 `farm_printer_status_history` 统一携带 `task_source`、`farm_job_id` 与 `task_match_reason`。外部任务不创建伪造的 `farm_print_job`，因此不会污染生产任务数量、成功率和操作员审计；其开始、状态变化和结束由设备状态历史记录。
