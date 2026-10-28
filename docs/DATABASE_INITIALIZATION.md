# 数据库初始化与历史迁移

适用产品：FabMatrix `1.0.0`。初始化脚本、分数据库的升级脚本及历史基线分别存放；目录整理不升级或清理已有数据库。

## 文件分工

| 入口 | 用途 |
| --- | --- |
| `src/main/resources/db/init/mysql-schema.sql` | MySQL 8.4 全新空库的完整结构，Compose 唯一初始化挂载 |
| `src/main/resources/db/migration/mysql/` | 已有 MySQL 数据库的增量升级、只读预检；按版本缺口选择，先备份 |
| `src/main/resources/db/init/sqlite-schema.sql` | Local Edition SQLite 初始化，仍配合 `LocalSchemaMigration` 补齐旧库 |
| `src/main/resources/db/migration/sqlite/` | SQLite 离线升级、诊断或测试依据，不由 Spring 自动扫描执行 |
| 两种迁移子目录中的 `dashboard-layout-precheck-*` | 动态布局升级前的只读预检 |
| `src/main/resources/db/archive/` | `farm.sql`、`V1__*`、`V2026_03_*` 历史基线与演进记录；不参与当前初始化 |

本项目没有 Flyway。目录名或 `V` 前缀不会使脚本自动执行。不要对已有库重跑 `farm.sql`，其中包含删表和旧业务数据。

目录入口见 `src/main/resources/db/README.md`。SQLite 原 `db/migration/local-schema.sql` 已改名为 `db/init/sqlite-schema.sql`；原迁移文件按数据库移动到子目录，编号及 SQL 内容保持不变。

## 新 MySQL 初始化

开发配置 `application-dev.yaml` 只负责连接数据库，不配置 `spring.sql.init`，不会在应用启动时重建已有 `farm`。

两份 Compose 均只挂载 `db/init/mysql-schema.sql` 为 `/docker-entrypoint-initdb.d/01-mysql-schema.sql`。MySQL 官方镜像只在空数据目录首次启动时执行它；更换脚本不会升级已有数据卷，也不要为使其生效而删除已有卷。

手工部署时先由运维创建空数据库、服务账号及权限，再通过 MySQL 客户端选择该空库并执行完整脚本。脚本不创建数据库或修改账号权限，故意使用普通 `CREATE TABLE`，遇到已有同名表会报错；它不是可重复升级入口，不要使用客户端 `--force` 忽略错误。

当前共 12 张表（新增 `farm_login_session` 登录会话表）：

- 业务资源：`farm_user`、`farm_printer`、`farm_print_file`、`farm_print_job`。
- 设备记录：`farm_printer_status_history`、`farm_printer_execution`。
- 派发计划：`farm_dispatch_plan`、`farm_dispatch_plan_item`（功能仍默认关闭）。
- 系统能力：`farm_dashboard_layout`、`farm_audit_log`、`farm_setup_state`。

只插入默认 4×12 布局和首次初始化状态两行；默认过道位于第 7 列之后。不导入历史设备、文件、任务、备份表或固定管理员密码。保留现有迁移中的兼容列、索引及排序规则，不在本次整理中重设计数据模型。

### 首个管理员

旧 `farm.sql` 中的账号不再随新库导入。`dev/prod` 的首次管理员接口默认关闭，因此新空库不能直接使用旧默认账号登录。

全新开发库可在服务器本机临时运行：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.address=127.0.0.1 --farm.security.first-admin-setup-enabled=true"
```

保持仅本机可访问，按 `API_HANDOFF.md` 的首次初始化契约：查询 setup 状态、从 loopback 获取 bootstrapToken、提交自选用户名/密码创建首个管理员。创建账号、默认文件夹和永久关闭 setup 在同一事务完成；之后停止临时实例，按普通配置启动。不要将此临时参数写入默认配置。正式部署需在受控服务器本机执行相同初始化流程，并保持生产密钥/CORS/Swagger 检查，不能直接把开放初始化的服务暴露到局域网。

## SQLite 初始化与升级

`local` profile 由 Spring 先执行 `classpath:db/init/sqlite-schema.sql`，再运行 `LocalSchemaMigration` 完成结构检查、补列、索引、布局和必要数据迁移。全新库和已有库均沿用此启动顺序，不需要人工导入 MySQL 脚本。

`CREATE TABLE IF NOT EXISTS` 不会给旧表增加字段，因此不能只更新初始化脚本。`migration/sqlite/` 下保留离线脚本与预检，部分脚本要求先由启动迁移补齐列，执行前须阅读各自说明；它们不会被自动扫描执行。

以后扩表同时更新 `init/mysql-schema.sql`、`init/sqlite-schema.sql`，追加 MySQL 增量迁移，并同步 SQLite 的 `LocalSchemaMigration`；有离线升级需要时再提供对应 SQLite SQL。分别验证全新初始化和旧库带数据升级。

## 已有数据库

保留原数据卷。先备份，再核对 Entity、Mapper、实际字段和已执行迁移，按依赖补齐缺口；不能仅凭表名存在就判断升级完成。尤其检查 `20` 号会话版本、`21` 号任务观测字段、`22` 号执行会话表，以及 `23-align-setup-state-and-job-default.sql` 的首次管理员状态/任务默认值。

备份表和历史业务数据的清理是独立操作，需确认范围。目录整理本身不会自动执行真实库迁移。

### 2026-10-28 开发 MySQL 补齐记录

用户授权后，已对 `localhost:3306/farm`（WSL `farm-mysql`，MySQL 8.4.11，持久化卷 `farm_farm_mysql_data`）执行 18、21、22、23 号迁移。18 号补齐任务查询索引，21 号补齐三个观测字段，22 号创建执行会话表，23 号补齐首次管理员状态及 `QUEUED` 默认值。已有 5 个用户，因此 setup 状态为 `initialized=1`，不开放首个管理员入口。

迁移前曾生成完整备份：`backup/mysql-gap-repair-20261028-123019/farm-before.sql`（203776 bytes，SHA-256 `A19D188F0A89AA77994BD99ED44C3358F8E89956623AAEB96C544FBE2DD79735`，已由 Git 忽略）。备份已恢复到无网络/tmpfs 临时 MySQL，四份脚本在副本上重复执行通过，再应用到真实开发库。实际迁移前后所有原表记录逐行对比一致；历史表按原有列投影比较，新增列取 `UNKNOWN/NULL/NULL`，未删除业务数据或重建持久化卷。

后续用户明确要求移除仓库根 `backup`，该目录已移入 Windows 回收站，上述路径现已不存在，仅作为历史验证记录保留。今后升级必须重新备份，不得把本记录当作当前存在的备份；此次目录清理没有删除真实数据库中的备份表。

迁移后 11 张业务/系统表的字段、默认值、索引和 CHECK 与当前初始化基线一致；额外保留三张备份表及修复审计表。用户/打印机/文件/任务仍为 5/49/50/10，状态历史为 932 条，执行会话表初始为空。恢复演练、幂等执行、MySQL 初始化/约束及全量 Maven 768 项测试通过；没有执行真实设备动作、应用部署或 Windows 安装验收。

本记录只适用于上述开发库。其他环境须独立检查和备份后再升级。MySQL DDL 不能依赖事务回滚；需要回退时先停应用并保留升级后的现场，再将迁移前备份恢复到隔离库验证，确认后切换或恢复目标库，不能直接把全库备份覆盖进正在使用的数据库。

## 验证

```powershell
.\mvnw.cmd test
```

安装有 Docker 的 Linux/WSL 中，在仓库根目录执行：

```sh
sh scripts/test-mysql-initialization.sh
```

验证脚本只使用本机已有的 `mysql:8.4` 镜像；创建无网络、无宿主端口、无命名数据卷的临时容器，验证首次初始化、空业务表、默认状态及关键唯一性/CHECK 约束，退出时清理该容器。不会连接 `farm-mysql`，也不会自动拉取镜像。

## 账号安全 24 号升级

新增 farm_user.must_change_password（既有账号默认 0）及 farm_login_session。SQLite 启动自动补齐，MySQL 手工执行一次 db/migration/mysql/24-account-security-mysql.sql，升级前停服并备份。新登录令牌必须包含 sessionId，升级后旧登录需重新登录。操作及回退说明见 [ACCOUNT_SECURITY.md](ACCOUNT_SECURITY.md)。
