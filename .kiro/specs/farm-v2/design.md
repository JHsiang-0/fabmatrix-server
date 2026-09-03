# Farm v2 技术设计：手动优先、可确认的批量派单

## 1. 设计目标

在保留现有 `/api/v1/auth`、`/printers`、`/print-files`、`/print-jobs` 和设备适配器基础的前提下，补齐一个明确的操作模型：

```text
手动单任务：选择文件/打印机 → 创建任务 → 派发 → 安全确认 → 上传或启动
用户批量操作：选择文件/打印机 → 生成预览 → 用户确认 → 逐项执行 → 返回逐项结果
后台自动派单：定时扫描 → 生成计划 → 前端确认 → 逐项执行（默认关闭）
```

三个流程共用任务、设备能力、锁、状态和错误模型。Controller 不直接判断 Klipper 或 RRF 的细节。

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
    require-confirmation: true
    interval: 10s
    max-items-per-run: 20
```

兼容迁移策略：

- 保留读取旧 `farm.tasks.enabled` 的过渡兼容，但新配置优先。
- 旧开关不能让 `scheduler` 在新配置缺省时意外开启；生产环境不再使用 `matchIfMissing=true` 作为自动派单默认值。
- `printer-ids` 为空时，`monitor` 默认不轮询，除非明确配置了“全部已启用设备”模式并记录告警。
- 设备监控和调度器分别记录启动、停止和每轮处理摘要。

## 4. 领域模型

### 4.1 派单计划

建议新增 `DispatchPlan` 和 `DispatchPlanItem`（可以先作为数据库表，也可以先用持久化 JSON/任务扩展表，最终必须支持幂等和审计）：

```text
DispatchPlan
- id
- mode: MANUAL | USER_BATCH | BACKGROUND_AUTO
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

### 5.5 可选自动派单设置

建议由 ADMIN 使用以下接口管理后台能力：

```text
GET /api/v1/dispatch/settings
PUT /api/v1/dispatch/settings
POST /api/v1/dispatch/preview
POST /api/v1/dispatch/confirm
```

这些接口复用 `DispatchPlan`。开关关闭时，预览可以供用户主动调用，但定时器不能执行；确认仍必须记录确认人和计划版本。是否允许管理员预先授权某种策略，需要在实现前明确写入权限测试，不能用隐式默认行为代替。

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

优先复用现有 `farm_print_file`、`farm_print_job` 和 `farm_printer`，仅在幂等/审计无法由现有字段表达时增加 `farm_dispatch_plan`、`farm_dispatch_plan_item`、状态事件或操作审计表。所有 SQL 必须提供增量迁移，先备份现有 Docker MySQL 数据，不使用 `down -v`。

发布顺序：

1. 先上线状态枚举、配置拆分和只读监控修复，旧自动调度保持关闭。
2. 上线单任务手动流程回归和批量上传。
3. 上线预览/确认和逐项执行。
4. 前端完成手动流程联调后，再以白名单设备启用监控。
5. 最后以默认关闭状态交付后台自动派单，经过前端确认、权限和并发验收后才允许管理员打开。
