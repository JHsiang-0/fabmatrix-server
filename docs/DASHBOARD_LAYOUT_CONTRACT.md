# SYNC-002 动态看板布局目标契约

## 1. 状态与边界

本文冻结并说明动态看板布局的后端契约，供 Controller、DTO/VO、OpenAPI、数据库迁移和前端联调使用。当前后端已提供 `/api/v1/dashboard-layout` 的 GET/PUT，前端已按 SYNC-002 接入。

首版只有一个农场级共享布局，不引入车间实体、用户私有布局或虚假车间切换。布局由服务端持久化，浏览器 localStorage 不能成为事实来源。

固定限制：

| 项目 | 限制 |
| --- | --- |
| 物理行数 | 1–30 |
| 物理列数 | 1–60 |
| 物理槽位总数 | 不超过 1800 |
| 过道总数 | 不超过 20 |
| 过道名称 | 1–12 个字符，去除首尾空格 |
| 过道尺寸 | 48–240 CSS px |
| 单次显式位置变化 | 不超过 100 台；缩容自动下架不计入该数量 |

默认布局为 4 行、12 个物理列，并在物理第 7 列之后插入一条 100px 的纵向“过道”。过道是视觉轨道，不占用或改变 `farm_printer.grid_row/grid_col`。

## 2. OpenAPI 资源

统一基础路径为 `/api/v1`，使用 `Authorization: Bearer <JWT>`，响应使用 `Result<T>`。

### 2.1 GET `/dashboard-layout`

权限：`ADMIN`、`OPERATOR`。

成功数据类型 `DashboardLayoutVO`：

```json
{
  "rowCount": 4,
  "columnCount": 12,
  "aisles": [
    {
      "id": "default-column-aisle",
      "axis": "COLUMN",
      "afterIndex": 7,
      "label": "过道",
      "size": 100
    }
  ],
  "version": 1,
  "limits": {
    "minRows": 1,
    "maxRows": 30,
    "minColumns": 1,
    "maxColumns": 60,
    "maxCells": 1800,
    "maxAisles": 20,
    "minAisleSize": 48,
    "maxAisleSize": 240
  },
  "updatedAt": "2026-09-11T10:00:00"
}
```

### 2.2 PUT `/dashboard-layout`

权限：仅 `ADMIN`；SecurityConfig 和 Service 均需校验。

请求类型 `DashboardLayoutSaveRequest`：

```json
{
  "expectedVersion": 1,
  "rowCount": 6,
  "columnCount": 16,
  "aisles": [
    {
      "id": "main-column",
      "axis": "COLUMN",
      "afterIndex": 8,
      "label": "主通道",
      "size": 100
    },
    {
      "id": "cross-row",
      "axis": "ROW",
      "afterIndex": 3,
      "label": "横向通道",
      "size": 80
    }
  ],
  "overflowPolicy": "REJECT",
  "positionChanges": [
    { "printerId": 311, "gridRow": 1, "gridCol": 2 },
    { "printerId": 312, "gridRow": null, "gridCol": null }
  ]
}
```

成功数据类型 `DashboardLayoutSaveResultVO`：

```json
{
  "layout": {
    "rowCount": 6,
    "columnCount": 16,
    "aisles": [],
    "version": 2,
    "limits": {},
    "updatedAt": "2026-09-11T10:01:00"
  },
  "changedPositions": [
    { "printerId": 311, "gridRow": 1, "gridCol": 2 },
    { "printerId": 312, "gridRow": null, "gridCol": null }
  ],
  "unallocatedPrinters": []
}
```

`layout.limits` 必须返回与 GET 相同的完整限制对象，不得实际返回空对象；示例使用空对象仅避免重复展开。

## 3. Schema 约束

### DashboardAisleDTO

- `id`：必填，1–64 个字符；同一请求中唯一。
- `axis`：必填枚举 `ROW|COLUMN`。
- `afterIndex`：整数。ROW 时为 `0..rowCount`，COLUMN 时为 `0..columnCount`。
- `label`：必填，trim 后 1–12 个字符。
- `size`：整数，48–240。
- 同一 `axis + afterIndex` 只能出现一次。

### DashboardPositionChangeDTO

- `printerId`：必填正数 Long；同一请求中唯一。
- `gridRow/gridCol`：必须同时为空或同时非空。
- 非空时均为正整数，并位于请求中的最终布局范围内。
- 同一请求中的非空目标槽位不得重复，也不得与未参与本次变化的打印机冲突。

### DashboardLayoutSaveRequest

- `expectedVersion`：必填正数 Long。
- `rowCount/columnCount`：必填整数并满足固定限制及乘积限制。
- `aisles`：必填数组，允许空数组，最多 20 条。
- `overflowPolicy`：必填枚举 `REJECT|UNALLOCATE`。
- `positionChanges`：必填数组，允许空数组，最多 100 条。

## 4. 错误响应

HTTP 400/404/409/422 使用具名 `DashboardLayoutProblemResultVO` envelope（OpenAPI 名为 `ResultDashboardLayoutProblem`）；503 保留通用基础设施 `Result` envelope。结构化详情只返回稳定原因、版本和设备摘要，不返回 SQL、堆栈或内部表结构。

`DashboardLayoutProblemVO`：

```json
{
  "reason": "OUT_OF_BOUNDS",
  "serverVersion": 2,
  "conflicts": [],
  "outOfBoundsPrinters": [
      { "printerId": "403", "name": "A-03", "gridRow": 6, "gridCol": 12 }
  ]
}
```

| HTTP / 业务码 | 场景 |
| --- | --- |
| 400 / 400 | 格式、范围、半空坐标、重复 ID/槽位、超过数量限制；请求内重复目标槽位原因为 `DUPLICATE_SLOT` |
| 401 / 401 | 未认证 |
| 403 / 403 | 非 ADMIN 写入 |
| 404 / 404 | 请求包含不存在的打印机 |
| 409 / 409 | `expectedVersion` 过期（`VERSION_CONFLICT`）或最终槽位冲突（`SLOT_CONFLICT`） |
| 422 / 422 | REJECT 缩容会排除已分配设备 |
| 500 / 500 | 未分类服务端错误 |
| 503 / 5001 | 数据库或依赖暂不可用 |

409 必须返回 `serverVersion`；422 必须返回完整 `outOfBoundsPrinters`。请求内重复目标槽位返回 `400/DUPLICATE_SLOT`，最终占位冲突返回 `409/SLOT_CONFLICT`。错误不产生布局、坐标、版本、审计或事件的部分写入。

## 5. 事务与坐标语义

- 保存时锁定 `farm_dashboard_layout(id=1)` 并比较 `expectedVersion`。
- 在内存中计算最终布局和全部相关设备的最终坐标，全部校验通过后才执行写入。
- 为支持交换，同一事务内先清空所有变更设备坐标，再写最终坐标。
- `REJECT` 发现越界设备时返回 422 且零写入。
- `UNALLOCATE` 的越界集合由服务端计算，并将每台设备的行列同时置空。
- 任一 SQL 失败必须抛出，禁止循环内捕获后继续并返回部分成功。
- 审计和 WebSocket 只在事务提交后发布。
- `machineNumber` 是设备资产字段；槽位标签由布局和坐标计算，绑定/移动不得更新 `machineNumber`。
- 新增打印机默认未分配；PrinterAddDTO/PrinterUpdateDTO 和旧 positions 入口不得绕过布局边界、版本和冲突校验。

## 6. WebSocket 扩展

沿用当前 `version=1` 顶层 envelope，已实现五类类型 `SNAPSHOT`、`PRINTER_STATUS`、`PRINTER_OFFLINE`、`JOB_STATUS`、`LAYOUT_UPDATED`。其中 `LAYOUT_UPDATED` 的布局字段仅位于 `data`：

```json
{
  "version": "1",
  "type": "LAYOUT_UPDATED",
  "eventId": "evt-layout-uuid",
  "sequence": 123,
  "printerId": null,
  "timestamp": 1789092060000,
  "data": {
    "stateSource": "LAYOUT",
    "layoutVersion": 2
  }
}
```

- `printerId` 必须为 null；后续需要调整 `FarmStatusMessage` 对非设备消息的校验。
- `layoutVersion` 必须为正数，且事件只在事务提交后发布。
- 客户端非编辑状态收到更高版本后重新 GET 布局和设备快照。
- 客户端编辑状态只标记草稿过期，不能自动覆盖草稿；旧版本保存由 409 拒绝。
- 旧客户端可以忽略未知布局事件，刷新后仍从 REST 获取权威布局。
- 布局事件不得携带或清空打印机业务状态、通信状态、任务、温度和进度。

## 7. OpenAPI 实现要求

后续实现必须为 Controller、请求 DTO、过道 DTO、位置 DTO、布局 VO、保存结果 VO 和问题详情 VO 添加 `@Operation`/`@Schema`：

- GET 明确 ADMIN/OPERATOR、默认布局和限制来源。
- PUT 明确 ADMIN、乐观锁、原子事务和 REJECT/UNALLOCATE 语义。
- 枚举、范围、nullable 坐标对和数组上限在 Schema 中可见。
- 409/422 的响应 Schema 使用 `Result<DashboardLayoutProblemVO>`，不能只显示通用字符串；当前 Controller/异常处理已按此返回。
- `/v3/api-docs` 的实际路径、字段和权限已按本文核对；后续修改 Controller 或 DTO 时必须同步更新本文和 API_HANDOFF。

## 8. 发布与弃用

数据库预检和迁移职责见 `docs/DASHBOARD_LAYOUT_MIGRATION.md`：MySQL 先执行只读预检，再执行 16 号脚本；SQLite 由 `LocalSchemaMigration` 在预检通过后安装 partial unique index 和项目自有坐标触发器。`local-schema.sql` 仅描述新库表级 CHECK，不是已有 SQLite 文件的独立迁移入口。

旧 `PUT /api/v1/printers/positions` 已弃用但保留 ADMIN 兼容调用；入口委托本资源的同一布局原子事务，直到弃用窗口结束再移除。前端只使用本资源的原子保存语义。
