# 数据库脚本目录

```text
db/
├── init/
│   └── sqlite-schema.sql      SQLite 初始化
├── migration/
│   └── sqlite/                SQLite 离线升级、诊断及只读预检
```

- SQLite：`application-local.yaml` 指向 `init/sqlite-schema.sql`，启动后仍由 `LocalSchemaMigration` 按原顺序检查、补齐结构和数据。只执行初始化 SQL 不等于完成所有启动迁移。
- `migration` 表示迁移，不是数据备份目录；其中 SQL 不会因目录名或文件编号自动执行。SQLite 离线脚本需遵守各自前置条件，不能替代启动迁移。

新增表、字段或索引时，同时维护 SQLite 初始化基线和 `LocalSchemaMigration` 的已有库升级路径；需要离线升级时追加 SQLite 脚本。详细运行说明见仓库 `docs/SINGLE_MACHINE.md`。
