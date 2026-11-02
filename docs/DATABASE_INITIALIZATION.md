# 数据库初始化与历史迁移

适用产品：FabMatrix `1.0.0` 单机 SQLite 运行时。数据库路径和启动配置见 [单机运行模式](./SINGLE_MACHINE.md)。

## 文件分工

- `src/main/resources/db/init/sqlite-schema.sql`：全新数据库初始化。
- `src/main/java/com/example/farm/config/LocalSchemaMigration.java`：启动时检查并升级已有数据库。
- `src/main/resources/db/migration/sqlite/`：离线诊断和手工升级脚本，不会自动扫描执行。

## SQLite 初始化与升级

`local` profile 由 Spring 先执行 `classpath:db/init/sqlite-schema.sql`，再运行 `LocalSchemaMigration` 完成结构检查、补列、索引、布局和必要数据迁移。全新库和已有库均沿用此启动顺序，不需要人工导入 MySQL 脚本。

`CREATE TABLE IF NOT EXISTS` 不会给旧表增加字段，因此不能只更新初始化脚本。`migration/sqlite/` 下保留离线脚本与预检，部分脚本要求先由启动迁移补齐列，执行前须阅读各自说明；它们不会被自动扫描执行。

以后扩表同时更新 `init/sqlite-schema.sql` 和 `LocalSchemaMigration`；有离线升级需要时再提供 SQLite SQL。分别验证全新初始化和旧库带数据升级。

## 验证

```powershell
.\mvnw.cmd test
```

## 账号安全 24 号升级

新增 farm_user.must_change_password（既有账号默认 0）及 farm_login_session。SQLite 启动时自动补齐。新登录令牌必须包含 sessionId，升级后旧登录需重新登录。操作及回退说明见 [ACCOUNT_SECURITY.md](ACCOUNT_SECURITY.md)。
