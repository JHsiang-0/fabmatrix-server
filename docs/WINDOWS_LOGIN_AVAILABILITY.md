# Windows Local Edition 登录后短时不可用排查（T16.31）

日期：2026-10-28。只读检查已安装实例，修复与复现均在隔离数据库中进行；没有停止实际 Windows 服务或修改实际业务数据库。

## 现场证据

- Windows 服务 FabMatrixServer 仍为 Running，监听 28081。日志确认 active profile 为 local，使用 SQLite 单连接池，不使用 application-prod.yaml 中的 MySQL/50 连接配置。
- 2026-10-28 16:48:48 登录接口报 HikariPool-1 连接等待超时 30001ms，total=1、active=1、idle=0、waiting=17。健康检查与其他请求共用数据库连接，因此会出现服务仍在运行但页面/托盘显示不可用。
- 后续本机 127.0.0.1 与当前 WLAN 地址的 /actuator/health 均返回 HTTP 200 / UP。现场有打印机连接超时日志，不能仅凭此认定后台进程退出。
- WLAN 为 Public，安装器的 FabMatrix Server (Private TCP) 入站规则只适用于 Private；进一步检查生效策略发现 Public 防火墙 Enabled=False，因此规则类型不匹配不能认定为当前阻断原因。本次不改网络类型或防火墙设置。

## 根因与修复

UserServiceImpl.login 的事务占用唯一 SQLite 连接，错误密码/不存在的账号/禁用或锁定账号调用 recordLoginFailure 时，原来的 REQUIRES_NEW 先挂起登录事务，再申请第二个连接；外层连接未释放，导致嵌套连接等待。即使当前没有其他请求，错误登录也会拖到连接超时并返回 500，失败审计方法体尚未执行。

修复仅调整登录拒绝路径：

1. 使用明确的 LoginRejectedException 表示预期认证拒绝；登录事务仅对这个异常不回滚。
2. recordLoginFailure 加入当前登录事务，提交拒绝审计，无需第二条连接。
3. 数据库异常、密码迁移/更新失败等普通异常仍回滚；没有增大 SQLite 连接池、关闭监控或改业务表结构。

## 复现与验证

- 新增 LoginFailurePoolIntegrationTest，真实 Local profile + SQLite + Hikari maximumPoolSize=1 + connectionTimeout=1500ms，HTTP 测试错误密码、不存在账号、禁用、连续失败锁定、审计持久化、健康检查及后续正确登录。
- 修复前：错误密码 HTTP 500，连接等待 1501ms，审计代理申请新事务失败；现场默认等待为 30 秒。
- IntelliJ 隔离测试调试：调用审计前 active=1、idle=0；修复前无法进入审计方法体。修复后进入同事务审计，错误登录 HTTP 401、355ms，请求结束 active=0。
- 全量 Maven 782/782 通过。调试仅暂停隔离测试的结果读取点，未暂停已安装服务；所有代理断点已移除，用户原有两个禁用异常断点保留。
- 修复包还需覆盖安装到现场实例后复核；旧版运行进程不会因源码或 dist 文件更新而自动获得修复。

## 配置使用

Windows 安装包显式使用 local，外部配置入口为安装目录 data/config。修改仓库 application-prod.yaml 不会直接修改已安装程序；不要为了此故障切换到 prod，否则会引入 MySQL/Redis/对象存储等不同依赖。

同机客户端可以使用 http://127.0.0.1:28081。其他电脑使用服务器当前局域网地址。不要仅为本次已复现的事务问题改网络类型或关闭防火墙。
