# Farm v2 技术设计：手动优先、可确认的批量派单

## 1. 设计目标

在保留现有 `/api/v1/auth`、`/printers`、`/print-files`、`/print-jobs` 和设备适配器基础的前提下，补齐一个明确的操作模型：

```text
手动单任务：选择文件/打印机 → 创建任务 → 派发 → 安全确认 → 上传或启动
用户批量操作：选择文件/打印机 → 生成预览 → 用户确认 → 逐项执行 → 返回逐项结果
v3 预留：后台自动派单（不属于 v2）
```

v2 的两个流程共用任务、设备能力、锁、状态和错误模型。Controller 不直接判断 Klipper 或 RRF 的细节。后台自动派单暂不进入 v2 状态机。

## 2. 当前代码基线与改造原因

当前实现中的 `PrinterMonitorTask` 和 `JobSchedulerTask` 都由 `farm.tasks.enabled` 控制，导致监控和调度无法独立启停；`JobSchedulerTask` 还按 `QUEUED` 扫描，而当前创建任务主要是 `PENDING`。此外，监控从 Redis 的打印机全量缓存读取可变的任务绑定，任务绑定变化后存在读取旧值的风险。

RRF 适配器已经具备基础 HTTP/命令能力，但当前状态解析主要依赖 `state.status`、文件位置和大小；RRF 的 `idle` 既可能是未打印，也可能是刚结束、取消或急停后的状态，不能直接映射为任务完成。因此 v2 先稳定状态和边界，再实现批量功能。

## 3. 配置设计

目标配置如下，具体命名以实现时的 Spring `@ConfigurationProperties` 为准：

```yaml
farm:
  monitor:
    enabled: false
    printer-ids: []
    interval: 5s
    concurrency: 10
  scheduler:
    enabled: false
    interval: 10s
    max-items-per-run: 0
```

兼容迁移策略：

- 保留读取旧 `farm.tasks.enabled` 的过渡兼容，但新配置优先。
- 旧开关不能让 `scheduler` 在新配置缺省时意外开启；生产环境不再使用 `matchIfMissing=true` 作为自动派单默认值。
- v2 不暴露调度器设置 API，也不在前端显示后台派单开关；`scheduler` 仅作为关闭状态的安全隔离配置和 v3 预留。
- `printer-ids` 为空时，`monitor` 默认不轮询，除非明确配置了“全部已启用设备”模式并记录告警。
- 设备监控和调度器分别记录启动、停止和每轮处理摘要。

## 4. 领域模型

### 4.1 派单计划

建议新增 `DispatchPlan` 和 `DispatchPlanItem`（可以先作为数据库表，也可以先用持久化 JSON/任务扩展表，最终必须支持幂等和审计）：

```text
DispatchPlan
- id
- mode: MANUAL | USER_BATCH
- strategy: ONE_TO_ONE | ROUND_ROBIN | AUTO_MATCH
- action: UPLOAD_ONLY | QUEUE | START_AFTER_CONFIRM
- status: PREVIEWED | CONFIRMED | EXECUTING | COMPLETED | PARTIAL_FAILED | EXPIRED | CANCELLED
- version
- confirmationTokenHash
- createdBy
- expiresAt
- confirmedAt

DispatchPlanItem
- id
- planId
- fileId
- printerId
- jobId
- status: PENDING | UPLOADING | UPLOADED | QUEUED | STARTING | STARTED | SUCCEEDED | FAILED | RETRYABLE
- reasonCode
- message
- attemptCount
- startedAt/completedAt
```

预览和确认不应把计划内容只放在前端。服务端必须保存版本和令牌摘要，以防客户端篡改打印机、文件或动作。

### 4.2 设备和任务状态

分开保存三类信息：

1. `observedDeviceState`：适配器从设备读取的原始状态和时间。
2. `desiredJobState`：用户或调度计划希望执行的动作。
3. `farmJobStatus`：Farm 任务状态，包括中间态、最终态和状态来源。

RRF 目标状态至少保留 `idle`、`processing`、`paused`、`pausing`、`halted`，并保存 `fileName`、`fileSize`、`filePosition`、`timesLeft`、`lastFileCancelled`、`lastFileAborted`、查询时间和原始响应摘要。`idle` 只有在完成证据或取消/急停已确认时才能结束任务，否则进入 `RECONCILING` 或保持中间态。

## 5. API 设计

### 5.1 已有接口的兼容原则

现有单任务接口保持 `/api/v1` 前缀和 `Result` 返回包装。已有安全派发、确认、启动、暂停、恢复、取消和急停接口不删除；改造其服务层状态校验和回写。当前真实地址和字段以 Controller 与 `API_HANDOFF.md` 为准。

### 5.2 批量文件上传

```http
POST /api/v1/print-files/batch-upload
Authorization: Bearer <token>
Content-Type: multipart/form-data
files=<file1>&files=<file2>
```

返回统一包装：

```json
{
  "code": 200,
  "message": "批量上传完成",
  "data": {
    "items": [
      {"fileId": 45, "fileName": "a.gcode", "status": "SUCCEEDED", "retryable": false},
      {"fileName": "b.gcode", "status": "FAILED", "errorCode": "FILE_TYPE_NOT_ALLOWED", "retryable": false}
    ],
    "successCount": 1,
    "failureCount": 1
  }
}
```

### 5.3 批量分配预览

```http
POST /api/v1/print-jobs/batch/preview
```

请求：

```json
{
  "fileIds": [45, 46],
  "printerIds": [564],
  "strategy": "ONE_TO_ONE",
  "action": "UPLOAD_ONLY"
}
```

响应数据至少包含：`planId`、`version`、`mode`、`strategy`、`action`、`items`、`conflicts`、`expiresAt`。每项包含文件、打印机、兼容性检查、占用状态和 `canExecute`。该接口只读，不创建任务、不锁设备、不访问设备写接口。

### 5.4 批量确认与执行

```http
POST /api/v1/print-jobs/batch/confirm
```

请求：

```json
{
  "planId": "dp_20260903_001",
  "version": 1,
  "itemIds": ["dpi_1", "dpi_2"],
  "confirmationToken": "一次性确认令牌"
}
```

确认接口负责校验计划、重新检查资源和原子占用，然后执行 `UPLOAD_ONLY` 或 `QUEUE`。`START_AFTER_CONFIRM` 仍要对每台设备执行安全确认；建议初版由前端继续调用现有单项 `safe/confirm` 和 `safe/start`，批量接口仅负责返回逐项任务 ID 与执行状态。

### 5.5 v2 不提供后台自动派单接口

以下接口不属于 v2，不实现、不在 Swagger 和前端契约中发布，留作 v3 重新评审：

```text
GET /api/v1/dispatch/settings
PUT /api/v1/dispatch/settings
POST /api/v1/dispatch/preview
POST /api/v1/dispatch/confirm
```

v3 如重新启用，应复用 `DispatchPlan`，并重新确定管理员授权、前端确认、审计和执行规则。不能用隐式默认行为代替明确授权。

## 6. 批量匹配算法

1. 校验文件列表、打印机列表、用户权限、文件状态和协议能力。
2. 读取数据库中最新的任务绑定、设备状态和占用版本。
3. 按策略生成候选：
   - `ONE_TO_ONE`：数量必须匹配，并检查每一项能力兼容。
   - `ROUND_ROBIN`：按可用设备循环分配，跳过离线/占用设备。
   - `AUTO_MATCH`：按设备协议、材料、喷嘴/能力、状态和用户指定约束评分。
4. 将不可分配项保留在预览中，给出稳定原因码；不为了凑数而隐式换设备。
5. 确认时使用数据库条件更新或乐观锁原子占用打印机，再创建任务；失败项释放本次未成功占用并返回逐项结果。
6. 重复确认根据 `planId + version` 返回第一次结果，不重复调用设备上传或启动。

## 7. 监控设计

`PrinterMonitorTask` 只处理 `monitor.printer-ids` 中的设备；每轮先获得数据库最新绑定，再调用对应协议适配器。Redis 只作为加速缓存，不作为任务绑定的唯一事实来源。设备查询失败更新健康信息和错误摘要，采用逐设备隔离与有限重试。

监控只负责“观测和同步”，不负责创建用户未确认的任务，不负责把普通 `PENDING` 任务变成已派发。`JobSchedulerTask` 只在 `scheduler.enabled=true` 时工作，并且必须明确处理的状态集合，不能继续依赖 `PENDING/QUEUED` 的历史不一致。

## 8. 协议适配设计

统一接口建议包括：

```java
DeviceSnapshot getStatus(Printer printer);
UploadResult upload(Printer printer, PrintFile file);
CommandResult start(Printer printer, PrintJob job);
CommandResult pause(Printer printer);
CommandResult resume(Printer printer);
CommandResult cancel(Printer printer);
CommandResult emergencyStop(Printer printer);
boolean isReachable(Printer printer);
```

`MoonrakerAdapter` 和 `RrfAdapter` 各自负责原始 HTTP/命令、超时、错误转换和状态解析。服务层只依赖统一结果，并根据 `observedState` 和 `commandResult` 更新 Farm 状态。RRF 急停后必须重新查询/重连确认，不能仅以 `M112` HTTP 返回成功作为任务完成。

## 9. 安全与权限

- `/batch/preview`、`/batch/confirm` 和设置接口必须走 JWT；设置接口仅 ADMIN，业务批量操作按 ADMIN/OPERATOR 规则校验。
- 服务层从当前登录用户取得操作者，不信任请求体中的 `operatorId`。
- 批量确认逐项校验文件归属、设备可操作权限、协议类型和任务状态。
- 执行日志记录 `planId`、`itemId`、用户、设备、文件、动作和结果，不记录令牌原文。
- 上传目录、RustFS、数据库和 Redis 的敏感配置只从环境变量/配置注入，不写入文档示例中的真实密钥。

## 10. WebSocket 与前端协作

WebSocket 使用 `/ws/farm-status`。目标消息格式：

```json
{
  "version": 1,
  "eventType": "PRINTER_STATUS_CHANGED",
  "eventId": "evt-uuid",
  "occurredAt": "2026-09-03T10:20:30.123+08:00",
  "printerId": 564,
  "jobId": 7,
  "data": {
    "status": "PRINTING",
    "rawStatus": "processing",
    "progress": 42.5,
    "stateSource": "RRF_POLL"
  }
}
```

前端收到事件后更新局部状态；连接建立、断线重连或事件版本不连续时，重新请求 REST 页面快照。批量执行使用 REST 返回的逐项结果，WebSocket 只补充设备/任务状态变化。

## 11. 数据库与发布策略

### 11.1 当前数据库的适用范围

现有 `farm_print_file`、`farm_print_job` 和 `farm_printer` 可以继续支撑单任务手动上传、派发和安全启动，但存在以下结构性限制：

- `farm_printer.current_job_id` 与 `farm_print_job.printer_id` 是同一绑定关系的两份写入，容易在异常或并发请求时不一致。
- 任务表没有版本号、幂等键和派单计划引用，无法可靠表达批量预览后的确认、重复确认和重启恢复。
- 打印机表主要保存当前业务状态，没有独立的最后观测时间、原始协议状态和状态来源；RRF `idle` 等状态无法留下充分证据。
- 任务表中的文件地址、耗材、喷嘴等字段是任务创建时的快照，但当前没有明确区分“文件当前属性”和“任务历史快照”。
- 当前仓库没有自动执行 Flyway；已有 SQL 是初始化脚本和手工增量脚本，数据库升级必须显式执行并记录版本。

因此 v2 不把现有表全部替换，而是先建立兼容的事实来源和幂等边界。

### 11.2 推荐的增量架构

第一阶段根据实际代码和现有数据增加以下能力：

1. `farm_print_job` 增加 `version`、`idempotency_key`、期望动作/状态、最后设备命令结果和状态来源字段；原有文件参数继续作为任务快照保留。
2. `farm_printer` 增加 `state_version`、`last_seen_at`、`last_raw_state`/错误摘要和状态来源字段；Redis 只做缓存。
3. 增加 `farm_dispatch_plan`、`farm_dispatch_plan_item`，保存批量预览内容、版本、确认摘要、过期时间、逐项执行状态和重试次数。
4. 增加 `farm_job_event` 或等价操作事件表，记录状态迁移和设备动作，满足重启恢复、审计和问题定位。
5. 派发确认时在事务内锁定打印机行（或使用后续新增的唯一活动绑定表），检查活动任务，再更新任务和打印机投影；不能依赖 Redis 锁单独保证数据库一致性。

如果后续并发量或状态复杂度证明 `current_job_id` 双写难以维护，再增加 `farm_printer_job_binding` 作为唯一活动绑定事实来源，保留旧字段作为过渡投影，完成数据校验后再下线旧字段。这个升级不需要删除历史打印任务。

### 11.3 迁移原则

- 所有结构调整使用编号明确的增量 SQL，执行前备份 Docker MySQL 数据，并提供回滚/兼容说明。
- 新字段先允许旧数据为空，由应用补齐；验证无冲突后再收紧非空、索引和唯一约束。
- 不删除 `farm_print_job` 历史记录，不用重建数据卷解决结构问题，不执行 `docker compose down -v`。
- 每次迁移同时更新实体、Mapper、Service、测试数据和 `API_HANDOFF.md`。

发布顺序：

1. 先上线状态枚举、配置拆分和只读监控修复，旧自动调度保持关闭。
2. 上线单任务手动流程回归和批量上传。
3. 上线预览/确认和逐项执行。
4. 前端完成手动流程联调后，再以白名单设备启用监控。
5. 后台自动派单不作为 v2 发布能力，后续另立 v3 规格；v2 只验收其不会被误启动。
