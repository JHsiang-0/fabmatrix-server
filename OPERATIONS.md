# Farm 后端运行与运维说明

## 启动顺序

1. 启动基础设施：`docker compose up -d`。
2. 检查 MySQL、Redis、RustFS：`docker compose ps`。
3. 等待 Compose 中 MySQL 和 Redis 的 `healthy` 状态，再启动应用：`mvn spring-boot:run`。
4. 无真实打印机时保持 `farm.tasks.enabled=false`；有真实设备并确认协议配置后再启用监控任务。

应用默认使用 `dev` profile，HTTP 端口为 `8080`。健康探针为 `GET /actuator/health`，只返回整体状态，不公开依赖详情；`info` 端点需要登录。

## 生产启动前检查

生产环境使用 `prod` profile，并通过环境变量提供 MySQL、Redis、RustFS、JWT 和管理员密钥。`ProductionSafetyValidator` 会拒绝开发默认密钥、通配 CORS 和公开 Swagger/OpenAPI。

启动前至少确认：

- `JWT_SECRET_KEY` 和 `ADMIN_SECRET_KEY` 已更换且妥善保存；
- `MYSQL_PASSWORD`、`REDIS_PASSWORD`、`RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY` 已配置；
- `farm.security.cors-allowed-origins` 只包含实际客户端来源；
- `farm.tasks.enabled` 仅在设备网络和协议适配器已验证后开启；
- 上传目录/对象存储桶可写，磁盘和 RustFS 容量有监控。

## 数据备份与升级

已有 Docker 数据卷不会重新执行 `/docker-entrypoint-initdb.d` 下的 SQL。执行增量迁移前先备份 MySQL、Redis 和 RustFS 数据；不要使用 `docker compose down -v`，该命令会删除命名数据卷。

当前项目没有 Flyway。任务状态、协议类型和状态历史使用 `src/main/resources/db/migration/04...06...sql` 手工升级；`02-current-schema.sql` 的新增列/索引检查已支持重复执行。

## 故障定位

- `GET /actuator/health` 返回 `DOWN`：先检查 MySQL/Redis 容器状态和应用配置；不要仅根据 Redis 缓存判断打印机是否存在。
- 打印机离线：检查打印机 IP、协议类型、Klipper 7125/RRF HTTP 端口和 API Key；应用会将设备错误映射为稳定业务码。
- 没有真实设备时不要开启监控任务，否则会持续产生设备不可达日志。
- 生产日志不得包含密码、JWT、打印机 API Key、数据库密码或 RustFS 密钥。
