# 单农场账号、岗位权限与登录会话

本功能不引入租户。固定岗位为 ADMIN、OPERATOR、VIEWER；用户仍只有一个角色。

## 密码和账号

- 管理员创建 OPERATOR 或 VIEWER，账号标记 `must_change_password=true`；首次本机初始化管理员自设密码，不强制重复修改。
- `POST /api/v1/auth/admin/users/{id}/reset-password` 接受 `password`、`confirmPassword`，长度沿用 6–20 位；只保存 BCrypt，强制下次改密，并使该账号全部旧登录失效。管理员不能通过该入口重置自己，应使用个人中心。
- 登录及 `/auth/me` 返回 `mustChangePassword` 和 `permissions`。强制改密期间，仅放行 GET `/auth/me`、POST `/auth/{当前用户ID}/change-password`、POST `/auth/logout`；其他已认证业务请求返回 HTTP 403、业务码 4031。WebSocket 不允许连接。
- 改密要求验证原密码且新旧不同；成功清除标记、递增 token_version、撤销全部会话，客户端重新登录。
- 禁用、改角色继续保护最后一个有效管理员。已有账号升级默认不强制改密。

## 岗位权限

`RolePermissions` 集中定义固定角色的权限名；JWT 过滤器为当前身份增加对应 authority，并向客户端返回相同权限列表。接口仍通过 SecurityConfig 的固定岗位规则授权，不能由客户端传入角色或权限扩大访问范围。

| 能力 | VIEWER | OPERATOR | ADMIN |
| --- | --- | --- | --- |
| 设备/看板读取 | 是 | 是 | 是 |
| 有权访问的文件、任务、历史导出 | 是 | 是 | 是 |
| 上传/维护文件、创建和控制任务 | 否 | 是 | 是 |
| 设备、布局、用户、审计管理 | 否 | 否 | 是 |

查看员沿用现有资源归属：可读取公有文件及本人资源，不能读取他人私有文件或任务；不能写入、派发、暂停、取消或急停。文件响应的写权限 capability 对查看员全部为 false。实时任务消息同样限定管理员或任务本人；设备状态仍是农场共享资源。直接访问设备自身网页的权限不受本服务控制。

## 登录会话

每次登录创建独立 `farm_login_session`，JWT 携带 sessionId，不保存完整 JWT。记录来源 IP（不信任客户端代理头）、限长 User-Agent、登录/最近活动/到期时间及撤销状态。最近活动每五分钟最多更新一次；有效会话不等于当前在线。

| 接口（均位于 `/api/v1/auth`） | 用途 |
| --- | --- |
| GET `/sessions` | 自己有效会话，最近 100 个，标记 current |
| POST `/sessions/{id}/revoke` | 撤销自己的指定会话 |
| POST `/sessions/revoke-others` | 保留当前会话，撤销其他登录 |
| POST `/logout` | 撤销当前会话 |
| GET `/admin/users/{id}/sessions` | 管理员查看员工有效会话 |
| POST `/admin/users/{id}/sessions/{sessionId}/revoke` | 管理员撤销指定员工会话 |
| POST `/admin/users/{id}/sessions/revoke-all` | 管理员撤销该账号全部会话 |

REST 同时检查签名、账号/角色/版本和会话撤销/到期状态；数据库不可用时拒绝鉴权。WebSocket 在连接、发送和心跳时检查；撤销事务提交后主动关闭对应连接。数据库会话状态持久化，服务重启不重新激活已撤销会话。没有 refresh token；到期重新登录。此次升级之前不含 sessionId 的旧 Token 必须重新登录。

审计复用 `/auth/admin/audit-logs`：新增 USER_PASSWORD_RESET、USER_PASSWORD_CHANGE、USER_FIRST_PASSWORD_CHANGE、USER_ROLE_CHANGE、SESSION_REVOKE、SESSION_REVOKE_ALL，角色调整记录前后角色。沿用操作者、目标账号、动作、结果及时间筛选；不记录密码、JWT 和完整请求内容。

## 升级和回退

1. 停止待升级实例，备份数据库和配置。此次开发没有操作实际开发/生产库。
2. SQLite：Windows Local Edition 在启动时由 LocalSchemaMigration 幂等补字段和会话表；离线脚本 `db/migration/sqlite/24-account-security-sqlite.sql` 仅执行一次，不与自动迁移重复执行。
3. MySQL：确认已有 23 号及之前所需迁移，手工执行一次 `db/migration/mysql/24-account-security-mysql.sql`。没有 Flyway 自动扫描。新空库使用更新后的 db/init 对应脚本。
4. 同步升级客户端和服务端，所有人员重新登录。既有账号密码和业务数据保持原值。
5. 回退优先恢复升级前数据库备份与旧版程序；若保留新结构回退旧程序，旧程序不会执行强制改密/会话撤销，应先停用尚未改密账号，不能把旧程序视作具有这些安全能力。不要直接删除业务表或重建数据卷。

## 验证入口

- `mvnw.cmd test`：岗位路由权限、强制改密过滤、独立会话与撤销、密码及最后管理员保护、SQLite 旧库升级。
- `python scripts/test-account-security.py`：临时 SQLite、随机 loopback 端口的真实 JWT/API 链路；复用现有 Node 内建 WebSocket 验证两个真实连接的独立撤销。结束仅清理自己启动的进程和临时数据，日志在 target/account-security。
- `wsl -d Ubuntu-LTS -- sh /mnt/d/WorkSpace/Java/fabmatrix-server/scripts/test-mysql-initialization.sh`：本机已缓存 MySQL 8.4 的隔离容器初始化/升级检查，无持久卷和公开端口。
- 客户端 `npm test`、`npm run lint:check`、`npm run release:gate -- --allow-dirty`；浏览器专项 `npx playwright test --project=browser --grep 账号安全`。
