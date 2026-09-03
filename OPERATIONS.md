# Farm 后端运行与运维说明

## 启动顺序

1. 启动基础设施：`docker compose up -d`。
2. 检查 MySQL、Redis、RustFS：`docker compose ps`。
3. 等待 Compose 中 MySQL 和 Redis 的 `healthy` 状态，再启动应用：`mvn spring-boot:run`。
4. 无真实打印机时保持 `farm.monitor.enabled=false` 和 `farm.scheduler.enabled=false`；有真实设备并确认协议配置后再启用监控任务。

应用默认使用 `dev` profile，HTTP 端口为 `8080`。健康探针为 `GET /actuator/health`，只返回整体状态，不公开依赖详情；`info` 端点需要登录。

## v1 Server Edition Docker 部署

正式 Docker 部署使用仓库根目录的 `docker-compose.server.yml`，不要把开发用 `docker-compose.yml` 当作正式配置。首次部署：

```bash
cp .env.server.example .env.server
# 编辑 .env.server，替换 CHANGE_ME 值，并填写已验证的 RUSTFS_IMAGE
mvn clean package -DskipTests
docker compose --env-file .env.server -f docker-compose.server.yml config
docker compose --env-file .env.server -f docker-compose.server.yml up -d --build
docker compose --env-file .env.server -f docker-compose.server.yml ps
```

正式 Compose 默认只暴露 Farm 的 HTTP 端口，MySQL、Redis、RustFS 只在 Compose 内部网络提供服务。`FARM_MONITOR_ENABLED` 和 `FARM_SCHEDULER_ENABLED` 默认都是 `false`；确认真实打印机白名单和协议后，只按需启用监控，后台调度不要在 v2 误开启。

已使用 `mvn -q package -DskipTests` 生成 jar，并成功构建本地镜像 `farm-server:local-20260903`；`docker compose config --quiet` 和 Server Edition Compose 配置校验均通过。正式发布前仍必须固定并验证 RustFS 镜像版本、填写真实 `.env.server` 并在发布环境执行启动/重启/备份恢复检查；`.env.server` 不得提交到 Git。首次初始化只适用于全新数据卷，已有数据卷升级必须先按下方备份步骤操作。增量脚本目前按 02 至 10 的编号顺序执行。

## v2 Local Edition（Windows/单机）

Local Edition 使用 SQLite 和本地文件目录，不需要启动 Docker、MySQL、Redis 或 RustFS：

```bash
mvn clean package -DskipTests
java -jar target/Farm-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=8080
```

默认数据目录为 `${user.home}/FarmData`（Windows 通常为当前用户目录下的 `FarmData`），其中 `farm.db` 是 SQLite 数据库，`files` 保存 G-code 和缩略图。可用 `FARM_DATA_DIR=D:/FarmData` 指定 Windows 数据根目录。已提供 `scripts/build-local-installer.ps1`，该脚本必须在 Windows Java 25 JDK 环境执行并依赖 `jpackage`；当前已验证脚本参数和 Local profile，Windows `.exe` 仍需在 Windows 发布机实际构建和验收。

Local Edition 启动会检查数据目录可写且可用空间不少于 100MB。备份必须同时保存 `farm.db` 和 `files` 目录，示例命令为 `powershell -ExecutionPolicy Bypass -File scripts/local-backup.ps1 -DataDir D:/FarmData`；恢复时先停止 Farm，再使用 `local-restore.ps1 -BackupDir D:/Backups/farm-local-... -DataDir D:/FarmData -Confirm RESTORE`，脚本会先把当前数据移到 `pre-restore-时间戳` 再恢复。Linux 使用 `sh scripts/local-restore.sh <备份目录> <数据目录> RESTORE`。恢复后重新启动并检查 `/actuator/health`、文件列表和任务列表，不要只恢复数据库而遗漏文件目录。

### 历史幽灵绑定修复记录

2026-09-03 在当前开发 MySQL 数据卷中发现 1 条任务指向已不存在打印机的历史绑定：任务 `1` -> 打印机 `289`。执行修复前已生成备份 `/tmp/farm-before-ghost-repair-20260903.sql`，随后运行 `scripts/repair-ghost-bindings.sql`。脚本创建并写入 `farm_binding_repair_audit`，解除任务的打印机绑定，并将活动任务置为 `RECONCILING`，不删除任务、不自动重新派单、不调用打印机。执行后残留幽灵绑定为 0。生产环境执行前必须使用生产数据库备份替换示例路径，并先停应用。

## 生产启动前检查

生产环境使用 `prod` profile，并通过环境变量提供 MySQL、Redis、RustFS、JWT 和管理员密钥。`ProductionSafetyValidator` 会拒绝开发默认密钥、通配 CORS 和公开 Swagger/OpenAPI。

启动前至少确认：

- `JWT_SECRET_KEY` 和 `ADMIN_SECRET_KEY` 已更换且妥善保存；
- `MYSQL_PASSWORD`、`REDIS_PASSWORD`、`RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY` 已配置；
- `farm.security.cors-allowed-origins` 只包含实际客户端来源；
  - `farm.monitor.enabled` 仅在设备网络和协议适配器已验证后开启；`farm.scheduler.enabled` 在 v2 保持关闭；
- 上传目录/对象存储桶可写，磁盘和 RustFS 容量有监控。

### 密钥轮换策略

- `JWT_SECRET_KEY` 使用密码管理器或操作系统 Secret Store 保存，建议使用 `openssl rand -base64 48` 生成随机值。
- 修改 `JWT_SECRET_KEY` 后重启应用会立即使旧 Token 全部失效，客户端需要重新登录；当前实现不支持新旧密钥并行校验，因此应安排维护窗口。
- `ADMIN_SECRET_KEY` 仅用于管理员敏感操作，也应独立保存并定期更换。更换后重启应用，并通过一次管理员敏感操作验证新值生效。
- 密钥不得写入 `application-prod.yaml`、Compose 文件、日志、接口响应或 WebSocket 消息；轮换前后都不要把密钥放进 Git 历史。

## 数据备份与升级

已有 Docker 数据卷不会重新执行 `/docker-entrypoint-initdb.d` 下的 SQL。执行增量迁移前先备份 MySQL、Redis 和 RustFS 数据；不要使用 `docker compose down -v`，该命令会删除命名数据卷。

当前项目没有 Flyway。任务状态、协议类型和状态历史使用 `src/main/resources/db/migration/04...06...sql` 手工升级；`02-current-schema.sql` 的新增列/索引检查已支持重复执行。

### 已有数据卷的升级步骤

以下命令在仓库根目录执行。备份目录仅为示例，执行前确认磁盘空间充足，并将密码通过环境变量提供，不要把真实密码写入脚本或提交到 Git。

```bash
export FARM_BACKUP_DIR="./backups/$(date +%Y%m%d-%H%M%S)"
export FARM_REDIS_PASSWORD='替换为当前 Redis 密码'
mkdir -p "$FARM_BACKUP_DIR"

# MySQL：事务一致性导出
docker compose exec -T mysql sh -c \
  'exec mysqldump --single-transaction --routines --events --triggers \
   -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  > "$FARM_BACKUP_DIR/farm.sql"

# Redis：导出 RDB 快照
docker compose exec -T -e REDISCLI_AUTH="$FARM_REDIS_PASSWORD" redis \
  redis-cli --no-auth-warning --rdb - \
  > "$FARM_BACKUP_DIR/redis.rdb"

# RustFS：备份对象存储数据卷
docker run --rm \
  -v farm_rustfs_data:/data:ro \
  -v "$PWD/$FARM_BACKUP_DIR":/backup \
  alpine:3.21 tar -czf /backup/rustfs-data.tar.gz -C /data .
```

确认备份文件可读后，再执行增量迁移。应用应先停止（使用 IDEA 的 Stop 或运行终端中的 Ctrl+C），基础设施保持运行：

```bash
docker compose up -d mysql redis rustfs
docker compose ps

for migration in \
  src/main/resources/db/migration/02-current-schema.sql \
  src/main/resources/db/migration/03-remove-customer-role.sql \
  src/main/resources/db/migration/04-normalize-print-job-status.sql \
  src/main/resources/db/migration/05-normalize-printer-firmware-type.sql \
  src/main/resources/db/migration/06-add-printer-status-history.sql \
  src/main/resources/db/migration/07-v2-dispatch-plan.sql \
  src/main/resources/db/migration/08-v2-atomic-printer-binding.sql \
  src/main/resources/db/migration/09-v2-print-job-idempotency.sql \
  src/main/resources/db/migration/10-v2-dispatch-resource-fingerprint.sql; do
  docker compose exec -T mysql sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < "$migration"
done
```

迁移后至少核对新增字段、历史表和规范化值，再启动应用：

```bash
docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e \
   "SHOW COLUMNS FROM farm_print_job LIKE '\''operator_id'\''; \
    SHOW COLUMNS FROM farm_print_file LIKE '\''rustfs_key'\''; \
    SHOW TABLES LIKE '\''farm_printer_status_history'\''; \
    SELECT DISTINCT status FROM farm_print_job; \
    SELECT DISTINCT firmware_type FROM farm_printer;"'

mvn spring-boot:run
```

如果迁移验证失败，先停止应用并保留备份，再根据失败脚本和数据库备份制定回滚方案；不要直接删除 Docker 数据卷。

### RustFS 容量、清理与备份策略

- RustFS 数据卷纳入定期备份，备份命令见本节上方；备份文件应保存到独立磁盘并定期抽样恢复验证。
- 当前没有按时间自动清理文件的后台任务。文件只允许通过业务接口删除，已关联打印任务的文件禁止删除；清理前由管理员确认业务保留期和任务引用关系。
- 生产环境应监控 RustFS 数据卷容量并设置预警阈值；容量不足时先暂停上传/清理无引用文件，再扩容或迁移对象存储，禁止直接删除 Docker 数据卷。

## 故障定位

- `GET /actuator/health` 返回 `DOWN`：先检查 MySQL/Redis 容器状态和应用配置；不要仅根据 Redis 缓存判断打印机是否存在。
- 打印机离线：检查打印机 IP、协议类型、Klipper 7125/RRF HTTP 端口和 API Key；应用会将设备错误映射为稳定业务码。
- 没有真实设备时不要开启监控任务，否则会持续产生设备不可达日志。
- 生产日志不得包含密码、JWT、打印机 API Key、数据库密码或 RustFS 密钥。
