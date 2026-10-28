# 数据库脚本目录

```text
db/
├── init/
│   ├── mysql-schema.sql       MySQL 全新空库初始化
│   └── sqlite-schema.sql      SQLite 初始化（原 migration/local-schema.sql）
├── migration/
│   ├── mysql/                 已有 MySQL 库增量升级、只读预检
│   └── sqlite/                SQLite 离线升级、诊断及只读预检
└── archive/                   farm.sql、V*.sql 历史基线，仅供追溯
```

- MySQL：Compose 首次启动空数据目录时执行 `init/mysql-schema.sql`，已有库升级需备份并手动执行所需增量脚本。
- SQLite：`application-local.yaml` 指向 `init/sqlite-schema.sql`，启动后仍由 `LocalSchemaMigration` 按原顺序检查、补齐结构和数据。只执行初始化 SQL 不等于完成所有启动迁移。
- `migration` 表示迁移，不是数据备份目录；其中 SQL 不会因目录名或文件编号自动执行。SQLite 离线脚本需遵守各自前置条件，不能替代启动迁移。
- `archive` 不参与当前初始化，尤其不要向已有库导入包含删表和旧数据的 `farm.sql`。
- 文件移动及 SQLite 初始化改名不改变 SQL 内容、迁移编号、执行顺序或已有数据库。

新增表/字段/索引时，同时维护对应数据库的初始化基线及已有库升级路径；SQLite 还需同步 `LocalSchemaMigration`。发布过的升级脚本保留，后续变更追加新编号，不重写历史。详细流程见仓库 `docs/DATABASE_INITIALIZATION.md`。
