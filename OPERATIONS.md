# Farm 后端运行与运维说明

## 启动顺序

1. 启动基础设施：`docker compose up -d`。
2. 检查 MySQL、Redis、RustFS：`docker compose ps`。
3. 等待 Compose 中 MySQL 和 Redis 的 `healthy` 状态，再启动应用：`mvn spring-boot:run`。
4. 无真实打印机时保持 `farm.monitor.enabled=false` 和 `farm.scheduler.enabled=false`；有真实设备并确认协议配置后再启用监控任务。

应用默认使用 `dev` profile，HTTP/WebSocket 端口为 `28081`，可由 `SERVER_PORT` 覆盖。健康探针为 `GET /actuator/health`，只返回整体状态，不公开依赖详情；`info` 端点需要登录。

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

正式 Compose 默认只暴露 Farm 的 HTTP 端口，MySQL、Redis、RustFS 只在 Compose 内部网络提供服务。`FARM_MONITOR_ENABLED`、`FARM_SCHEDULER_ENABLED` 和 `FARM_BATCH_DISPATCH_ENABLED` 默认都是 `false`；确认真实打印机协议后，只按需启用监控，具体设备通过管理员 API 设置 `monitorEnabled`，后台调度和 V1 批量派发不要在准正式版误开启。批量派发三个入口关闭时返回 HTTP 404、业务码 `10004`（`FEATURE_DISABLED`）和统一 `{code,message,data,timestamp}` 响应；V3 完成验收后才可显式设置 `FARM_BATCH_DISPATCH_ENABLED=true`。

已使用 `mvn -q package -DskipTests` 生成 jar，并成功构建本地镜像 `farm-server:local-20260903`；`docker compose config --quiet` 和 Server Edition Compose 配置校验均通过。正式发布前仍必须固定并验证 RustFS 镜像版本、填写真实 `.env.server` 并在发布环境执行启动/重启/备份恢复检查；`.env.server` 不得提交到 Git。首次初始化只适用于全新数据卷，已有数据卷升级必须先按下方备份步骤操作。增量脚本按 `02` 至 `18` 的依赖顺序执行，其中 `11-printer-monitor-reliability.sql` 必须早于同编号的 `11-v2-user-status-and-batch-recovery.sql`，16 号动态布局迁移必须在只读预检无结果后执行，18 号任务查询迁移负责旧状态回填和分页索引。TC-007 从 19 号迁移开始；TC-004–TC-006、TC-008 不在本次迁移范围。

## v2 Local Edition（Windows/单机）

Local Edition 使用 SQLite 和本地文件目录，不需要启动 Docker、MySQL、Redis 或 RustFS：

```bash
mvn clean package -DskipTests
java -jar target/fabmatrix-server-1.0.0.jar --spring.profiles.active=local --server.port=28081
```

手动运行 Local profile 时默认数据目录为 `${user.home}/FarmData`，可用 `FARM_DATA_DIR=D:/FarmData` 指定。正式 Windows 安装包使用统一数据根：安装在系统盘时为 `C:\ProgramData\FabMatrix`，安装在非系统盘时为 `{app}\data`；两者均包含 `config\application.yml`、`database\farm.db`、`files`、`logs`、`backups`。正式唯一构建入口为 `scripts/windows/build-installer.ps1`，旧的 `scripts/build-local-installer.ps1` 仅委托该入口。

Windows 安装包默认程序目录为 `{autopf}\FabMatrix`，固定服务名为 `FabMatrixServer`，新安装监听 `0.0.0.0:28081`，Local profile 使用 SQLite、本地 files、关闭 scheduler 和 Swagger/OpenAPI，并启用 graceful shutdown。安装器只解析一次实际端口，将同一值写入 WinSW 服务级 `SERVER_PORT`、Windows Firewall `Private` 入站规则和 `{app}\service-state\effective-port.txt`；普通 Users 对该非敏感元数据仅有读取/执行权限，不开放 Public，也不放宽数据 ACL。配置/database/backups 仅允许 SYSTEM/Administrators 完全控制，files/logs 对 Users 只读。升级前捕获旧服务 StartType/运行状态并停止旧/新服务和托盘，升级后恢复该状态；全新安装才由选项决定。旧 `C:\ProgramData\Farm` 仅在新根未初始化时先创建迁移备份，再按 `data\farm.db -> database\farm.db`、`uploads -> files`、`config -> config` 非破坏性迁移；迁移脚本以原子 JSON 结果声明本次是否真实迁移，服务升级或真实 legacy migration 才将旧纯数字默认 8080 迁为 requested default 28081，自定义端口保持不变且显式 `SERVER_PORT` 始终优先，普通 fresh config 8080 不会被粗暴改写。真实旧配置即使没有 datasource URL，也会补齐 `${FARM_DATA_DIR}` database/files/logs 路径并保留 CORS 和密钥。服务安装由 Inno `[Code]` 同步检查退出码，失败会明确中止并尽力恢复仍存在的原服务状态。普通卸载删除服务、托盘/程序和防火墙规则，但保留当前 DataRoot、旧版 `%ProgramData%\Farm` 与 `%ProgramData%\FabMatrix` 安装诊断；交互式完全卸载必须主动选择并完成默认聚焦“否”的第二次不可逆确认，随后在停服停托盘后清理当前 DataRoot、经 `data\farm.db`/非空 `config`/非空 `uploads` 识别的旧版 Farm 根，以及与当前根去重的 FabMatrix 项目残留。系统盘 DataRoot 为 `%ProgramData%\FabMatrix`，非系统盘为 `{app}\data`；`/SILENT` 默认绝不删除数据，命令行完全卸载必须显式使用 `/PURGEDATA=CONFIRM`。卸载器在 `usUninstall` 阶段同步执行一次清理并强校验进程退出码，失败即中止，交互模式提示全部位置、静默模式只记录并返回失败；每个目标精确匹配策略路径，逐节点复检完整驱动器根、目标和所有已存在祖先/后代的 reparse point，且仅用 LiteralPath 做非递归后序删除。

托盘控制器 `FabMatrixTray.exe` 使用与后端共享的 jpackage Java runtime，以普通权限运行，通过 SCM 和可读的 `{app}\service-state\effective-port.txt` 探测 `http://127.0.0.1:<安装时生效端口>/actuator/health`，并用于显示服务/健康状态及复制局域网/本机地址。元数据缺失或无效时，才依次回退托盘进程的 `SERVER_PORT`、数据根 `config\application.yml`（若可读）和 `28081`；配置仍支持纯数字和 `${SERVER_PORT:旧值}`。托盘支持刷新、正常启动/停止/重启、打开 files/logs、管理员编辑配置和退出。UAC 调用使用 `-EncodedCommand` 处理含空格/单引号的路径；config/database/backups 的私有 ACL 不为托盘放宽。退出托盘不会停止服务；无 SystemTray 时友好退出。

全新 Local Edition 数据目录不预置管理员账号。首次启动后由前端调用 `GET /api/v1/auth/setup/status`，在 `setupAvailable=true` 时调用 `POST /api/v1/auth/setup/admin` 创建管理员；成功后直接返回 JWT。该接口只在 Local Edition 且数据库没有任何用户时可用，初始化后返回 409，不能再次匿名创建管理员。

Local Edition 启动会检查数据目录可写且可用空间不少于 100MB。备份/恢复脚本要求先停止 `FabMatrixServer`，脚本不会自动停服；安装包中的路径为 `{app}/maintenance/local-backup.ps1` 和 `{app}/maintenance/local-restore.ps1`，共享 helper 也位于该目录。备份至少保存 `database/farm.db`、`files` 和 `config/application.yml`，配置路径统一为 `${FARM_DATA_DIR}` 占位。恢复先完成同卷 staging，再把当前三者整体移动到 `backups/pre-restore-*` 后完整换入，并重新施加 Windows 私有/只读 ACL；ACL 失败进入回滚，不会遗留旧 SQLite WAL/SHM 或孤儿文件。Linux 使用 `sh scripts/local-restore.sh <备份目录> <数据根> RESTORE`，保持整体替换但不施加 Windows ACL，需维持合适的 owner/mode。恢复后重新启动并检查 `/actuator/health`、文件列表和任务列表，不要只恢复数据库而遗漏文件目录。

安装包构建前可运行 `powershell -File scripts/windows/test-installer-contract.ps1` 做非破坏性路径、保留数据、防火墙、服务和托盘编译回归；完整构建为 `powershell -File scripts/windows/build-installer.ps1`，也支持 `-Mode AppImage` 和 `-Mode Payload`。不要运行真实备份/恢复脚本作为构建验证。

jpackage 后端主 launcher 使用 console 模式且不带 `-Xrs`，WinSW 停止超时 30 秒，大于 Spring graceful shutdown 的 20 秒。干净 Windows 安装验收必须核对 WinSW 安装态日志，确认停止和升级时完成 graceful shutdown；脚本级验证不能替代这项验收。

### 已登记打印机重新开机后的 IP 恢复

Local Edition 默认开启 `farm.discovery.enabled`；基础/dev/test 默认关闭。自动恢复要求全局监控开启，并只扫描已启用单台监控且符合监控白名单的已登记设备。默认启动 10 秒后首次检查，此后每 5 分钟巡检；连续通信失败达到监控阈值时也可触发，共用同一限频和在途任务，不产生自动派单或打印动作。

管理员可在打印机管理页点击独立“同步设备 IP”，查看扫描范围、进度和逐项结果。普通刷新只重新读取列表。MAC 和协议确认一致后，服务端保留原设备 ID、名称、编号、位置和任务绑定，仅同步地址并等待监控确认在线；未找到、无 MAC、重复身份或地址冲突的设备保持原登记记录，不必先删除设备重新添加。

默认 `farm.discovery.subnets: []` 从合格已有设备的旧私有 IP 推断网段。需要限定范围或设备可能跨网段换地址时，以管理员身份编辑运行配置并重启服务，例如：

```yaml
farm:
  discovery:
    enabled: true
    subnets: ["192.168.0", "192.168.1"]
    initial-delay: 10s
    interval: 5m
    timeout: 2m
    concurrency: 16
```

`subnets` 是私有 IPv4 的三段 `/24` 前缀，不填写 `.0/24` 或单个完整 IP；最多 8 个，检查 `.1` 至 `.254`。公网、非法范围、过多网段会失败并给出原因；无旧 IP 且范围未配置时不能推断。自动周期至少 30 秒，并发 1–64，默认总体网络发现超时 2 分钟。不需要执行 SQL、重建 Docker 数据卷或开启 scheduler。

身份保护开启且设备登记有效 MAC 时，每次监控和上传/启动/控制动作前都匿名重新核验；身份未通过返回 503，不发送已有密钥或物理命令，也不提前释放任务绑定。无有效 MAC 的旧设备保留兼容行为，但无法自动恢复地址。

匿名网络信息和精确目标 ARP 用于身份核对，ARP 中的网关 MAC 不能当作打印机身份。Moonraker 401/403 可通过匿名 `/access/info` 识别协议、同网段 ARP 补足 MAC；RRF 只读发现仅断开独立 sessionKey 会话，无 key 时等待设备 TTL，避免干扰已有正常会话。

鉴权设备不公开 MAC 且跨 VLAN/路由不能取得真实 ARP 时，固定 DHCP 仍会被已开启的身份保护拒绝状态与动作。优先让服务端与设备处于同一可 ARP 网络；管理员若选择固定 DHCP、关闭 `farm.discovery.enabled` 并手工维护地址，也同步关闭新增身份保护。Windows 安装器契约已通过，真实关机换 IP、鉴权/跨网段和新 Windows 安装包安装验收尚未执行，详见 [规格任务](.kiro/specs/printer-ip-rediscovery/tasks.md)。

### 打印历史 Excel/PDF 导出

打印历史页的导出弹窗默认复制列表筛选，可以在弹窗独立编辑状态、文件、打印机、创建人和结束时间后直接下载全部匹配的终态任务，也可恢复列表条件或重置全部历史；无需先查询列表，导出不会改变列表。管理员可查询全部或指定创建人，操作员只查询本人创建的任务。Excel 打开即显示第一张“明细”打印记录表格，统计信息保留在第二张“汇总”；PDF 使用 A4 横向紧凑表格和重复表头以减少页数。Excel 默认最多 10000 条，PDF 默认最多 1000 条，文件最多 20 MiB；超限需缩小日期、状态或其他筛选范围，不会自动截断。无匹配结果仍下载有效的零记录报告。

如需降低同步导出开销，可由管理员在运行配置设置 `farm.export.xlsx-max-rows`（1–10000）及 `farm.export.pdf-max-rows`（1–1000），或设置对应环境变量 `FARM_EXPORT_XLSX_MAX_ROWS/FARM_EXPORT_PDF_MAX_ROWS` 后重启。无新增数据库迁移或对象存储配置。PDF 随应用附带 OFL 中文字体，无需 Windows 安装特定字体。

报告中的记录条数按每次任务尝试计数，不能视为打印件数；经过时长包含暂停，缺失/异常时间留空。报告只包含平台任务，不含设备外部打印。字段和错误语义见 `API_HANDOFF.md`，本轮测试及真实安装/数据验收边界见 [导出任务](.kiro/specs/print-history-export/tasks.md)。

### 历史幽灵绑定修复记录

2026-09-03 在当前开发 MySQL 数据卷中发现 1 条任务指向已不存在打印机的历史绑定：任务 `1` -> 打印机 `289`。执行修复前已生成备份 `/tmp/farm-before-ghost-repair-20260903.sql`，随后运行 `scripts/repair-ghost-bindings.sql`。脚本创建并写入 `farm_binding_repair_audit`，解除任务的打印机绑定，并将活动任务置为 `RECONCILING`，不删除任务、不自动重新派单、不调用打印机。执行后残留幽灵绑定为 0。生产环境执行前必须使用生产数据库备份替换示例路径，并先停应用。

## 生产启动前检查

生产环境使用 `prod` profile，并通过环境变量提供 MySQL、Redis、RustFS、JWT 和管理员密钥。`ProductionSafetyValidator` 会拒绝开发默认密钥、通配 CORS 和公开 Swagger/OpenAPI。

启动前至少确认：

- `JWT_SECRET_KEY` 和 `ADMIN_SECRET_KEY` 已更换且妥善保存；
- `MYSQL_PASSWORD`、`REDIS_PASSWORD`、`RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY` 已配置；
- `farm.security.cors-allowed-origins` 只包含实际客户端来源；
- `farm.monitor.enabled` 仅在设备网络和协议适配器已验证后开启；`farm.scheduler.enabled` 和 `farm.features.batch-dispatch.enabled` 在准正式版保持关闭；
- 上传目录/对象存储桶可写，磁盘和 RustFS 容量有监控。

### 密钥轮换策略

- `JWT_SECRET_KEY` 使用密码管理器或操作系统 Secret Store 保存，建议使用 `openssl rand -base64 48` 生成随机值。
- 修改 `JWT_SECRET_KEY` 后重启应用会立即使旧 Token 全部失效，客户端需要重新登录；当前实现不支持新旧密钥并行校验，因此应安排维护窗口。
- `ADMIN_SECRET_KEY` 仅用于管理员敏感操作，也应独立保存并定期更换。更换后重启应用，并通过一次管理员敏感操作验证新值生效。
- 密钥不得写入 `application-prod.yaml`、Compose 文件、日志、接口响应或 WebSocket 消息；轮换前后都不要把密钥放进 Git 历史。

## 数据备份与升级

已有 Docker 数据卷不会重新执行 `/docker-entrypoint-initdb.d` 下的 SQL。执行增量迁移前先备份 MySQL、Redis 和 RustFS 数据；不要使用 `docker compose down -v`，该命令会删除命名数据卷。

当前项目没有 Flyway。任务状态、协议类型和状态历史使用 `src/main/resources/db/migration/04...06...sql` 手工升级；TC-003 使用 `18-v2-print-job-query-semantics-mysql.sql`（MySQL）或 `18-v2-print-job-query-semantics-sqlite.sql`（SQLite）回填旧任务状态并建立查询索引；TC-007 使用 `19-v2-print-job-audit-model-mysql.sql` 或 `19-v2-print-job-audit-model-sqlite.sql` 补齐历史快照/重试 lineage 字段、旧任务 `root=id/attempt=1`、当前资源尽力回填和唯一索引。operator 快照只对 `started_at` 非空、具备实际开始证据的旧任务回填；旧 READY 且 `started_at` 为空时保持 null。19 号脚本及 LocalSchemaMigration 均支持重复执行；SQLite 离线脚本要求列已由 LocalSchemaMigration 或 fresh local-schema 补齐。历史改名和已丢失字段不可恢复。`02-current-schema.sql` 的新增列/索引检查已支持重复执行。任务时间契约冻结为 Asia/Shanghai 的不带偏移 wall-clock，不提供可覆盖时区配置：MySQL 使用服务器/JDBC `+08:00`，SQLite fresh 及 assign/root/retry Mapper 写入使用 `datetime('now', '+8 hours')`，应用查询直接绑定 local datetime，不转换已存时间。

SYNC-002 动态布局必须先执行 `dashboard-layout-precheck-mysql.sql`，确认没有半空、重复、非正数、非整数或超出默认
4×12 的坐标，再执行 `16-v2-dashboard-layout-mysql.sql`。`farm.sql` 和 `02-current-schema.sql` 不创建或写入
`farm_dashboard_layout`；布局表及默认行只有 16 号脚本在预检通过后创建。完整迁移、真实定义核对和回滚步骤见
[`docs/DASHBOARD_LAYOUT_MIGRATION.md`](docs/DASHBOARD_LAYOUT_MIGRATION.md)。

Local Edition 不直接把 SQLite 手工 SQL 当作 schema 迁移入口：`LocalSchemaMigration` 先预检，成功后才安装布局表、
partial unique index 和项目自有坐标触发器；`local-schema.sql` 只描述新库表级 CHECK。不要在启动迁移前手工创建同名
索引或触发器，错误同名对象必须保留并阻断启动。

`11-printer-monitor-reliability.sql` 使用 MySQL 8 元数据检查，监控字段和 `idx_printer_monitor_enabled` 索引缺失时才添加，可安全重复执行。旧 `farm_printer` 记录新增 `monitor_enabled` 时默认值为 `0`，不会因迁移自动纳入轮询；管理员确认设备后再通过监控 API 启用。

UI-004.20 文件可见性迁移使用 `15-v2-print-file-visibility-mysql.sql`（MySQL）。SQLite 没有
`ALTER TABLE ADD COLUMN IF NOT EXISTS`，因此 `15-v2-print-file-visibility-sqlite.sql` 是在列已由
`LocalSchemaMigration` 补齐后执行的诊断/回填脚本；Local Edition 启动迁移是 SQLite 的唯一可重复 schema 入口，不能把手工脚本当作可重复的列迁移执行。两条入口都会把默认目录整棵子树固定为 `PRIVATE`，根文件归档只处理旧/PRIVATE 文件并明确保留 PUBLIC 根文件；同时输出公共候选、孤儿、父级非文件夹、循环、超深、真实命名空间下的同级目录重名和父子可见性冲突诊断。结构或可见性异常会阻止 Local 启动。执行前必须备份数据库并保留脚本输出。

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

# SYNC-002：先执行只读预检；输出必须为空，否则停止并保留现场
docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < src/main/resources/db/migration/dashboard-layout-precheck-mysql.sql

for migration in \
  src/main/resources/db/migration/02-current-schema.sql \
  src/main/resources/db/migration/03-remove-customer-role.sql \
  src/main/resources/db/migration/04-normalize-print-job-status.sql \
  src/main/resources/db/migration/05-normalize-printer-firmware-type.sql \
  src/main/resources/db/migration/06-add-printer-status-history.sql \
  src/main/resources/db/migration/07-v2-dispatch-plan.sql \
  src/main/resources/db/migration/08-v2-atomic-printer-binding.sql \
  src/main/resources/db/migration/09-v2-print-job-idempotency.sql \
  src/main/resources/db/migration/10-v2-dispatch-resource-fingerprint.sql \
  src/main/resources/db/migration/11-printer-monitor-reliability.sql \
  src/main/resources/db/migration/11-v2-user-status-and-batch-recovery.sql \
  src/main/resources/db/migration/12-v2-audit-log.sql \
  src/main/resources/db/migration/13-v2-print-file-default-folders.sql \
  src/main/resources/db/migration/15-v2-print-file-visibility-mysql.sql \
  src/main/resources/db/migration/16-v2-dashboard-layout-mysql.sql \
  src/main/resources/db/migration/18-v2-print-job-query-semantics-mysql.sql \
  src/main/resources/db/migration/19-v2-print-job-audit-model-mysql.sql; do
  docker compose exec -T mysql sh -c \
    'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < "$migration"
done
```

TC-003 任务查询迁移必须在上述脚本之后执行：

```bash
docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < src/main/resources/db/migration/18-v2-print-job-query-semantics-mysql.sql
```

18 号脚本将 `PENDING/MANUAL` 回填为 `QUEUED`、将 `CANCELED` 回填为 `CANCELLED`，并创建活动/历史分页索引；19 号脚本补齐快照和重试 lineage。执行前先备份；Local Edition 启动时由 `LocalSchemaMigration` 执行相同的幂等补齐、回填和索引创建，手工 SQLite 脚本仅用于离线升级或核对。不要对真实数据库自动执行迁移。

迁移后至少核对新增字段、历史表和规范化值，再启动应用：

```bash
docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e \
   "SHOW COLUMNS FROM farm_print_job LIKE '\''operator_id'\''; \
    SHOW COLUMNS FROM farm_print_file LIKE '\''rustfs_key'\''; \
    SHOW COLUMNS FROM farm_printer LIKE '\''monitor_enabled'\''; \
    SHOW COLUMNS FROM farm_printer LIKE '\''connection_status'\''; \
    SHOW COLUMNS FROM farm_printer LIKE '\''last_seen_at'\''; \
    SHOW COLUMNS FROM farm_printer LIKE '\''last_communication_error'\''; \
    SHOW COLUMNS FROM farm_printer LIKE '\''consecutive_failure_count'\''; \
    SHOW COLUMNS FROM farm_printer LIKE '\''last_known_status'\''; \
    SHOW COLUMNS FROM farm_printer LIKE '\''last_protocol_state'\''; \
    SHOW COLUMNS FROM farm_printer LIKE '\''last_device_uptime'\''; \
    SHOW INDEX FROM farm_printer WHERE Key_name = '''idx_printer_monitor_enabled'''; \
    SHOW TABLES LIKE '\''farm_printer_status_history'\''; \
    SELECT user_id, COUNT(*) AS default_folder_count FROM farm_print_file \
      WHERE is_folder = 1 AND is_default_folder = 1 GROUP BY user_id \
      HAVING COUNT(*) <> 1; \
    SELECT COUNT(*) AS root_file_count FROM farm_print_file \
      WHERE is_folder = 0 AND parent_id IS NULL; \
    SELECT DISTINCT status FROM farm_print_job; \
    SELECT DISTINCT firmware_type FROM farm_printer; \
    SELECT visibility, COUNT(*) FROM farm_print_file GROUP BY visibility;"'

# SYNC-002：只在 precheck 无结果且 16 号迁移成功后核对布局对象
docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e \
   "SHOW CREATE TABLE farm_printer; SHOW CREATE TABLE farm_dashboard_layout; \
    SHOW INDEX FROM farm_printer WHERE Key_name = '\''uk_farm_printer_grid_position'\''; \
    SELECT id, row_count, column_count, version FROM farm_dashboard_layout WHERE id = 1;"'

mvn spring-boot:run
```

`13-v2-print-file-default-folders.sql` 只会补齐可确定的默认目录并将根目录中的旧/PRIVATE 文件归入唯一默认目录，明确不移动 PUBLIC 根文件；`15-v2` 会再次将默认目录整棵子树（包括已有 PUBLIC 节点）固定为 `PRIVATE`。发现同名冲突、重复默认目录、孤儿/循环、非文件夹父级或超过两级的目录时会输出诊断并保留现场，不应把校验结果视为可忽略警告；同名诊断只针对目录，PRIVATE 根按 `user_id` 隔离、PUBLIC 根共享、子目录按 `parent_id` 判断。Local Edition 会在启动前阻断这些异常；MySQL 手工迁移后也必须先停止应用、保留备份并人工处理，再重新执行迁移，不要直接删除数据卷。

如果迁移验证失败，先停止应用并保留备份，再根据失败脚本和数据库备份制定回滚方案；不要直接删除 Docker 数据卷。

### 文件库可见性发布与回滚

迁移默认把默认目录整棵子树标记为 `PRIVATE`，并保留其他历史节点的显式可见性；脚本输出的每个 PUBLIC 候选及其全部后代清单是审批预览，不是发布操作。管理员应核对归属、父级、后代数量、孤儿/循环/超过两级目录和同级目录重名，形成审批记录后，才可对明确的公共根及其全部后代执行受控更新。禁止只更新根目录而留下可见性不一致的后代，也禁止用 `is_default_folder=0` 作为发布条件。

发布前先导出候选 ID 和当前值；如需回滚，只能根据该审计快照将本次发布的 ID 恢复为 `PRIVATE`，不得删除目录、文件或对象存储内容。发布后核对 `visibility` 分布、默认目录及后代均为 `PRIVATE`，并用 ADMIN/OPERATOR 分别验证树、分页、下载和创建任务。

### RustFS 容量、清理与备份策略

- RustFS 数据卷纳入定期备份，备份命令见本节上方；备份文件应保存到独立磁盘并定期抽样恢复验证。
- 当前没有按时间自动清理文件的后台任务。文件只允许通过业务接口删除，已关联打印任务的文件禁止删除；清理前由管理员确认业务保留期和任务引用关系。
- 生产环境应监控 RustFS 数据卷容量并设置预警阈值；容量不足时先暂停上传/清理无引用文件，再扩容或迁移对象存储，禁止直接删除 Docker 数据卷。

## 故障定位

- `GET /actuator/health` 返回 `DOWN`：先检查 MySQL/Redis 容器状态和应用配置；不要仅根据 Redis 缓存判断打印机是否存在。
- 打印机离线：检查打印机 IP、协议类型、Klipper 7125/RRF HTTP 端口和 API Key；应用会将设备错误映射为稳定业务码。
- 重新开机后 IP 变化：管理员查看“同步设备 IP”结果，并检查有效 MAC、监控开关/白名单和 `farm.discovery.subnets`；身份未确认优先检查同一可 ARP 网络，固定 DHCP 不能绕过已开启的身份保护，不能以另一台设备对旧地址的响应当作恢复成功。
- 没有真实设备时不要开启监控任务，否则会持续产生设备不可达日志。
- 生产日志不得包含密码、JWT、打印机 API Key、数据库密码或 RustFS 密钥。
