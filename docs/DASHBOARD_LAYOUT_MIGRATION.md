# SYNC-002.1 看板布局数据库迁移说明

本说明适用于 FarmMatrix Server Edition 的 MySQL 8.4 和 Local Edition 的 SQLite。布局表是农场级单例，`id` 固定为 1；默认布局为 4 行、12 列，并在物理第 7 列后放置 100px 的纵向过道。`aisles_json` 统一为 `TEXT`，JSON 结构由后续应用层校验；`updated_by` 可空且不建立外键。

`farm_printer.grid_row/grid_col` 的成对与正数 CHECK、联合唯一索引属于打印机表结构：MySQL 新库由 `farm.sql` 定义，`02-current-schema.sql` 负责补齐缺失字段/约束/索引。两份脚本都不创建或写入 `farm_dashboard_layout`；布局表及默认行只有编号 16 在预检成功后创建。

## 发布顺序

严格按“数据库预检/迁移 → 后端 → 前端”发布。迁移成功并完成数据库验证前，不部署依赖布局表的后端接口或前端功能。

## MySQL 8.4

1. 先备份目标数据库，例如：

   ```sh
   mysqldump --single-transaction --routines --triggers -u <user> -p farm > farm-before-sync-002.1.sql
   ```

2. 执行只读预检：

   ```sh
   mysql -u <user> -p farm < src/main/resources/db/migration/dashboard-layout-precheck-mysql.sql
   ```

   结果必须为空。结果列固定为 `issueType,id,name,gridRow,gridCol`，异常类型包括半空坐标、重复槽位、非正数和默认 4×12 越界。不得跳过异常、改坐标或用 `farm.sql` 修复已有库。

3. 预检为空后，手工执行正式编号 16 迁移：

   ```sh
   mysql -u <user> -p farm < src/main/resources/db/migration/16-v2-dashboard-layout-mysql.sql
   ```

   脚本会在布局表任何 DDL 前再次输出并阻断异常；已有同名错误结构的布局表也会明确失败。它会核对列顺序/类型/可空性、启用的五项 CHECK 及其真实表达式、唯一主键集合和 `created_at`/`updated_at` 的默认值与更新时间语义。重复执行安全，已存在的合法单例不会覆盖版本、过道或更新时间。编号 16 使用临时例程缓存失败状态，并在成功/失败路径都先删除例程，再由客户端执行明确的失败语句终止批处理，因此不会残留 `farm_dashboard_layout_migrate`。

4. 验证：

   ```sql
   SELECT id, row_count, column_count, aisles_json, version, updated_by
   FROM farm_dashboard_layout;
   SELECT COUNT(*) FROM farm_dashboard_layout;
   SELECT COUNT(*) FROM farm_printer
   WHERE (grid_row IS NULL) <> (grid_col IS NULL)
      OR (grid_row IS NOT NULL AND grid_col IS NOT NULL
          AND (grid_row <= 0 OR grid_col <= 0));
   ```

   第一条应返回 `id=1,row_count=4,column_count=12,version=1`（已存在布局再次迁移时保留原值），第二条为 1，第三条为 0。再检查 `SHOW CREATE TABLE farm_dashboard_layout`，确认单例、1–30 行、1–60 列、乘积不超过 1800、`version>0` 五项 CHECK 约束均存在。

## SQLite

Local Edition 启动时 `local-schema.sql` 只负责新库 `farm_printer` 的成对/正数坐标 CHECK，不创建布局表、partial index 或坐标触发器。partial unique index 和 INSERT/UPDATE 坐标触发器由 `LocalSchemaMigration` 在完整只读预检成功后负责创建；项目自有的同名触发器会在预检后安全重建，索引则严格核对 `unique=1`、列集合和完整 partial `WHERE` 语义。布局表及默认行同样由 `LocalSchemaMigration` 在预检成功后创建。已有 SQLite 文件也可先手工执行只读预检：

```sh
sqlite3 /path/to/farm.db < src/main/resources/db/migration/dashboard-layout-precheck-sqlite.sql
```

结果必须为空。SQLite 额外报告非 INTEGER（包含文本和 REAL）坐标。随后启动 Local Edition，`LocalSchemaMigration` 会再次预检，创建/验证布局表和默认行，并补齐坐标部分唯一索引及 INSERT/UPDATE 触发器；重复启动无害。异常坐标或同名错误结构会在任何布局表写入前阻止启动，原坐标保持不变。

## 回滚与失败新卷处理

MySQL 回滚使用迁移前备份恢复到新的隔离数据库，经抽样校验后再切换服务；不要对生产表执行猜测性的反向 DDL，也不要删除原数据卷。SQLite 回滚前停止应用，复制保存当前 `.db` 文件，再用迁移前备份替换；保留失败文件供审计。

全新 Compose MySQL 数据卷初始化时会按稳定顺序只读挂载 `farm.sql`、`02-current-schema.sql`、历史脚本以及 14、15、16 增量脚本。若新卷初始化在 16 失败，先保存容器日志和卷中的诊断信息，删除失败的新卷后重新创建；只有确认该卷不包含需要保留的数据时才允许执行针对该新卷的卷清理。`farm.sql` 是新库种子脚本，禁止用于已有库，因为它会 DROP 并重建业务表。

当前未在真实 MySQL 容器或既有生产数据卷上执行验收；后端自动测试使用隔离 SQLite，MySQL 侧完成脚本静态契约检查，不能替代现场 MySQL 验收。

## SYNC-002.9 发布收口

升级前必须保存预检输出、数据库备份和脚本版本；成功后核对 `SHOW CREATE TABLE farm_printer`、`SHOW CREATE TABLE farm_dashboard_layout`、联合唯一索引、布局单例行及时间列语义。Local Edition 必须以 `LocalSchemaMigration` 为唯一 schema 入口，不能把 `local-schema.sql` 或 partial index 手工 SQL 当作已有库迁移。

旧 `PUT /api/v1/printers/positions` 已停用并进入弃用窗口：发布后前端只使用 `/api/v1/dashboard-layout`，旧客户端不得继续写位置；需要兼容时仅保留只读页面，待所有客户端升级后移除旧入口。详细请求、错误和 WebSocket 语义见 `API_HANDOFF.md` 与 `docs/DASHBOARD_LAYOUT_CONTRACT.md`。

## 管理员操作

管理员在前端编辑布局时先核对当前版本和越界清单，再保存草稿。缩容默认选择 `REJECT`；只有确认设备下架并完成二次确认后才选择 `UNALLOCATE`。收到 `409/VERSION_CONFLICT` 时重新载入权威布局，不得覆盖其他管理员的保存；收到 `422/OUT_OF_BOUNDS` 时先调整布局或明确下架清单。保存成功后检查布局版本、设备坐标成对性和实时看板，发现异常立即停止继续编辑并保留审计信息。
