# Farm 项目协作指南

本文档是 Codex 及其他代码代理在本仓库中工作的依据。用户的明确要求优先于本文档；涉及数据删除、重建 Docker 数据卷、修改生产数据或其他不可逆操作时，必须先确认目标和影响范围。

## 1. 项目定位

Farm 是一个本地 3D 打印农场管理后端：一个局域网服务端，多个浏览器或客户端连接，统一管理打印机、打印文件、打印任务和状态看板。

当前产品目标不是互联网 SaaS，不需要手机号注册、邮箱验证、公开注册、多租户或联网账户体系。账号由管理员在本地服务端创建。

当前仓库只包含后端代码，没有 Vue、React 或其他前端工程。前端项目如果在其他目录，先确认其实际路径，不要假设本仓库存在前端文件。

## 2. 技术栈与运行要求

- Java 25（`pom.xml` 中 `java.version` 为 25）
- Spring Boot 4.0.3
- Spring MVC、Spring Security、WebSocket、Validation、Actuator
- MyBatis-Plus 3.5.15
- MySQL 8.x
- Redis 7.x
- RustFS（S3 兼容对象存储）
- JWT：`auth0/java-jwt` 4.4.0
- SpringDoc OpenAPI 3.0.2
- Maven
- JaCoCo 0.8.14（兼容 Java 25）

不要把编译目标改回 Java 5，也不要用较老的 JDK 代替 Java 25。若 IDEA 提示 JVM target 5，检查项目、模块和 Maven 的编译器设置是否统一为 25。

## 3. 目录结构

```text
src/main/java/com/example/farm/
├── config/       Spring、安全、Redis、WebSocket、日志配置
├── controller/   HTTP API、WebSocket、Moonraker 模拟接口
├── service/      服务接口及 impl 实现
├── mapper/       MyBatis-Plus Mapper
├── entity/       数据实体、DTO、VO
├── task/         打印机监控和任务调度定时任务
└── common/       响应、异常、JWT、Redis、G-code、RustFS 等通用工具

src/main/resources/
├── application*.yaml       基础、dev、prod 配置
├── mapper/                  MyBatis XML
├── db/migration/             建表、升级和 Docker 初始化 SQL
└── logback-spring.xml       日志配置
```

主要调用关系：

```text
Controller -> Service -> Mapper -> Entity
                    └-> common/utils（Redis、JWT、RustFS、设备 HTTP 调用）
```

## 4. 常用命令

在仓库根目录执行：

```bash
# 编译
mvn compile

# 运行全部测试
mvn test

# 运行指定测试类或方法
mvn test -Dtest=ClassName
mvn test -Dtest=ClassName#methodName

# 打包
mvn package
mvn package -DskipTests

# 运行开发环境（application.yaml 默认激活 dev）
mvn spring-boot:run

# 清理构建产物；只影响 target，不影响数据库和 Docker 数据卷
mvn clean
```

修改 Java、SecurityConfig、配置绑定或 SQL 后，至少运行 `mvn test`。当前测试数量很少，测试通过只能说明上下文基本可以启动，不能替代接口权限和真实设备测试。

## 5. Spring Profile 与端口

- `application.yaml` 默认激活 `dev`。
- `dev` 使用本机映射的 MySQL、Redis、RustFS，端口通常为 `8080`。
- `dev` 中 `farm.tasks.enabled=false`，没有真实 Klipper 打印机时不要打开，否则监控任务会轮询不存在的设备并产生错误日志。
- `test` 使用 H2，关闭调度任务和 WebSocket，服务端口为随机端口。
- `prod` 使用环境变量配置敏感信息，端口默认为 `8080`，生产环境调度任务默认会启用（任务条件缺省时 `matchIfMissing=true`）。

生产环境至少需要提供：

- `MYSQL_PASSWORD` 或完整的 `MYSQL_URL`、`MYSQL_USERNAME`
- `REDIS_PASSWORD` 等 Redis 参数
- `RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY`
- `JWT_SECRET_KEY`
- `ADMIN_SECRET_KEY`

不要把生产密钥直接写进 `application-prod.yaml` 或提交到版本库。`application-prod.yaml` 是后端运行配置，不是前端配置。

如果 IDEA 中已有应用占用 8080，先检查端口，或为测试应用指定其他端口：

```bash
ss -ltnp | rg ':8080'
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

## 6. Docker Compose 与数据安全

`docker-compose.yml` 当前提供：

- `farm-mysql`：MySQL 8.4，宿主机端口 3306
- `farm-redis`：Redis 7.4，宿主机端口 6379
- `farm-rustfs`：RustFS，S3 端口 9000，控制台端口 9001

常用命令：

```bash
docker compose up -d
docker compose ps
docker compose logs -f mysql
docker compose logs -f redis
docker compose down
```

`down` 不会删除命名数据卷；不要在没有明确授权时使用 `docker compose down -v`，因为它会删除 MySQL、Redis 和 RustFS 数据。

MySQL 初始化脚本按挂载顺序执行：

1. `src/main/resources/db/migration/farm.sql`
2. `src/main/resources/db/migration/02-current-schema.sql`
3. `src/main/resources/db/migration/03-remove-customer-role.sql`
4. `src/main/resources/db/migration/04-normalize-print-job-status.sql`

这些脚本只会在全新的 MySQL 数据卷初始化时自动执行。已有数据卷不会因为修改脚本而重新执行；升级已有数据库前先备份，再手动执行对应 SQL。

仓库没有配置 Flyway 依赖，不能假设 `src/main/resources/db/migration/` 下的 `V*.sql` 会被 Spring 自动执行。`V1__init_schema.sql` 等文件是历史/手工迁移脚本，当前 Docker 启动实际使用的是 `farm.sql` 和显式挂载的增量脚本。已有数据卷不会自动执行新增脚本，升级前需备份并手动执行对应 SQL。

## 7. 数据模型与数据库注意事项

核心表：

- `farm_user`：本地账号、BCrypt 密码、角色
- `farm_printer`：打印机名称、IP、MAC、固件类型、状态、耗材、喷嘴、物理网格位置
- `farm_print_file`：G-code/切片文件、解析出的工艺参数、上传用户、虚拟目录、RustFS key
- `farm_print_job`：文件、打印机、发起用户、现场操作员、状态、优先级、进度和错误信息

当前实体与历史 SQL 存在过演进，修改表结构时必须以当前实体、Mapper XML、现有 Docker 数据库三者共同核对。尤其注意 `farm_print_file` 的虚拟目录字段和 `farm_print_job.operator_id` 是后续增量补齐的字段。

## 8. 认证与角色权限

认证链路：

1. `POST /api/v1/auth/login` 校验用户名和密码。
2. `UserServiceImpl` 使用 BCrypt 或兼容的密码迁移逻辑验证密码。
3. 登录响应返回 JWT、用户 ID、用户名和 `role`。
4. `JwtAuthenticationFilter` 从 `Authorization: Bearer <token>` 读取 JWT，并创建 `ROLE_ADMIN` 或 `ROLE_OPERATOR` 权限。

当前只保留两个角色：

- `ADMIN`：管理员，管理本地账号、打印机配置、任务调度和全部运营操作。
- `OPERATOR`：操作员，查看设备、上传文件、创建和控制打印任务，能够执行现场暂停/急停和安全确认流程。

账号规则：

- 新账号由管理员创建，服务层固定创建为 `OPERATOR`。
- `POST /api/v1/auth/register` 仍然存在，但已经不是匿名注册，只允许 `ADMIN` 调用。
- 推荐管理员使用 `POST /api/v1/auth/admin/users` 创建操作员。
- 管理员用户更新接口只能设置 `ADMIN` 或 `OPERATOR`。
- 密码由后端校验：长度 6-20 位，必须包含大写字母、小写字母和数字。前端校验只能改善提示，不能代替后端校验。
- 不要重新引入 CUSTOMER、手机号验证、邮箱验证或公开注册，除非用户明确改变产品目标。

当前路由级权限：

| 接口范围 | ADMIN | OPERATOR |
|---|---:|---:|
| 登录 `/api/v1/auth/login` | 免认证 | 免认证 |
| 用户管理 `/api/v1/auth/admin/**` | ✓ | — |
| 创建账号 `/api/v1/auth/register`、`/api/v1/auth/admin/users` | ✓ | — |
| 查看打印机 `GET /api/v1/printers/**` | ✓ | ✓ |
| 修改/删除/扫描打印机 | ✓ | — |
| 查看文件和分页查询 | ✓ | ✓ |
| 上传、建目录、删除文件 | ✓ | ✓ |
| 查看任务和队列 | ✓ | ✓ |
| 创建、取消、派发、启动任务 | ✓ | ✓ |
| 暂停、急停 `/api/v1/control/**` | ✓ | ✓ |

`SecurityConfig` 是当前主要的路由授权入口。不要只在前端隐藏按钮；新增敏感接口必须同时在后端限制角色，并在服务层检查资源归属或业务状态。

P0.4 资源边界已经在服务层落实：打印机是本地农场共享资源；ADMIN 可查看和管理全部文件、任务，OPERATOR 只能访问自己创建的文件和任务。操作员身份以当前 JWT 为准，不能由前端提交的 `operatorId` 指定。文件夹的父级也必须属于当前用户（ADMIN 除外）。

P0.5 输入约束已经启用：打印机、任务、派发、安全确认、文件夹请求使用 Jakarta Bean Validation；批量添加、批量删除和位置更新单次最多 100 项；文件上传扩展名读取 `farm.file.allowed-types`，大小读取 `farm.file.max-file-size`。RustFS 访问失败统一返回存储错误（业务码 5003）。

## 9. 主要 API

公共业务前缀为 `/api/v1`，成功响应统一使用 `Result`，认证请求携带：

```http
Authorization: Bearer <token>
```

主要接口：

- 用户：`/auth/login`、个人资料、修改密码、管理员用户管理
- 打印机：`/printers/page`、`add`、`update`、`delete/{id}`、`scan`、`batch-add`、`positions`、`unallocated`
- 文件：`/print-files/upload`、`page`、`/{id}/download`、删除、虚拟目录
- 任务：`/print-jobs/queue`、`page`、`create`、`/{id}`、`safe/assign`、`safe/confirm`、`safe/start`
- 控制：`/control/{id}/emergency-stop`、`/control/{id}/pause`

SpringDoc 默认可访问：`http://localhost:8080/swagger-ui.html`。Swagger 和 `/v3/api-docs` 当前公开，生产环境是否公开应根据局域网边界另行决定。

`API_DOCUMENT.md` 是手工文档，可能落后于代码；以 Controller 和 `SecurityConfig` 为准。当前文档中关于匿名注册的旧说明需要按实际权限理解。

## 10. 打印机协议与任务流程

当前设备通信实现是 Klipper/Moonraker：

- `MoonrakerApiClient` 默认访问打印机的 7125 端口。
- 状态查询、暂停、取消、急停、文件上传等调用 Moonraker HTTP API。
- `PrinterServiceImpl` 支持按 MAC 地址 Upsert，处理 DHCP 导致的 IP 变化。
- 扫描接口当前也是围绕 Klipper/Moonraker 7125 端口设计。

当前项目还不能通过修改 `firmwareType` 自动支持 RRF 3.7。若接入 RRF，应增加协议适配层，例如按设备协议选择 Klipper/Moonraker 适配器或 RRF HTTP 适配器；不要在现有 Moonraker 客户端中简单替换 URL。

安全打印流程是：

1. 管理员或操作员派发任务到空闲打印机，状态变为 `ASSIGNED`。
2. 现场操作员确认热床安全。
3. 现场操作员调用启动接口，后端校验 `is_safe_to_print` 后才真正启动。
4. 启动后记录 `operator_id`，并清除安全确认标记。

注意：创建任务当前使用 `PENDING`，而自动调度器只查询 `QUEUED`。如果要重新启用自动派单，必须先明确任务状态设计并补充测试，不能只打开配置开关。

## 11. 定时任务、Redis 与 WebSocket

- `PrinterMonitorTask` 每 5 秒轮询打印机，并通过 `WebSocketServer` 广播状态。
- `JobSchedulerTask` 每 10 秒扫描任务，使用 Redis 分布式锁避免多实例重复调度。
- 两个任务都受 `farm.tasks.enabled` 控制。
- Redis 停止、连接工厂已停止或打印机不可达时，可能出现 Lettuce 或 Moonraker 异常；没有真实打印机时保持 dev 任务关闭。
- WebSocket 地址为 `/ws/farm-status`，握手必须携带 `/ws/farm-status?token=<JWT>`（兼容 `access_token` 参数）；缺少、无效或过期 Token 的连接会被拒绝。认证通过后当前仍是农场级状态广播，不是按用户或打印机细分的订阅通道。
- `WebSocketConfig` 受 `farm.websocket.enabled` 控制，测试环境已关闭。

## 12. Moonraker 模拟接口

`MoonrakerMockController` 用于开发时兼容 OrcaSlicer 的部分上传接口，映射 `/server`、`/printer`、`/machine` 等根路径。

该控制器当前只在 `dev`、`test` profile 加载，正式环境不要依赖它作为真实打印机协议实现。若配置 `farm.moonraker-api-key`，模拟接口会校验 `X-Api-Key`；开发环境未配置时校验会放宽，仅适合本地使用。

## 13. 日志与异常

- 业务异常使用 `BusinessException`，统一由 `GlobalExceptionHandler` 转换为项目响应格式。
- 业务操作、数据变化、访问拒绝和慢操作优先使用 `LogUtil`。
- 不记录密码、JWT、完整 API Key、数据库密码或 RustFS 密钥。
- 生产日志级别主要为 WARN/INFO；开发环境为了排查问题开启了更详细的 Mapper 和 Web 日志。
- 外部设备、Redis、RustFS 调用失败时要保留可定位的设备 ID、任务 ID或文件 ID，但避免输出敏感值。

## 14. 测试与已知限制

当前 `src/test` 主要是 Spring 上下文测试，测试环境使用 H2，关闭 Redis 相关真实操作、定时任务和 WebSocket。测试启动时可能看到 H2 没有完整业务表、RustFS 网络访问受限等警告，但应以最终测试结果为准；新增功能应尽量补充真实的 Controller 权限测试和 Service 单元测试。

后续修改优先关注：

1. ADMIN/OPERATOR 接口的 401/403 集成测试；
2. 任务状态 `PENDING` 与 `QUEUED` 的统一；
3. WebSocket 的身份认证、连接上限和断线处理；
4. RRF 与 Klipper 的协议适配；
5. 生产环境 CORS、Swagger、JWT 密钥和管理员敏感接口的收敛；
6. 文件、任务和打印机资源的服务层归属校验；
7. MySQL 迁移脚本的可重复执行和版本管理。

## 15. 修改工作规范

1. 先阅读相关 Controller、Service、Mapper、Entity 和配置，再修改，不要只根据文档猜接口。
2. 先检查 `git status` 和目标文件的 diff；工作区已有修改属于用户，不能用 reset、checkout 或批量格式化覆盖。
3. 使用精确补丁修改文件，避免无关的换行、编码或格式变化。
4. 修改权限时同时检查路由权限、服务层资源归属和前端可见性；前端隐藏按钮不是安全措施。
5. 修改数据库前先确认当前数据库是否为持久化 Docker 数据卷，并备份或提供可回滚 SQL。
6. 不为本地单农场场景引入注册中心、手机号验证码、多租户或复杂 RBAC，除非用户明确要求。
7. 完成后运行与风险相称的验证，至少报告修改文件、数据库操作、测试命令和剩余限制。

## 16. 交付检查清单

- [ ] 代码和配置与当前 profile 一致
- [ ] ADMIN/OPERATOR 权限没有被前端逻辑替代
- [ ] 没有把敏感信息写入新代码、文档或日志
- [ ] 数据库变更有明确作用范围，未误删数据卷
- [ ] `mvn test` 或针对性测试已运行
- [ ] API 文档、配置说明和实际接口没有明显冲突
- [ ] 已检查工作区中的无关修改并避免覆盖
