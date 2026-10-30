# Server 代码与协作规范

适用范围：`1.0.0` 后端维护，farm-v2 T16.32。与产品边界和操作约束共同遵循 [AGENTS.md](../AGENTS.md)。

## 分层与职责

- `controller` 负责 HTTP 参数、校验和响应；业务规则进入 Service，设备协议进入 `protocol`。
- `service` 表达业务意图；`mapper` 承载数据库访问；`entity`、`entity/dto`、`entity/vo` 分别表达持久化、输入和输出。
- `common/storage` 与 `common/export` 维护已有存储和导出抽象；不要让 Controller 直接调用 Mapper 或固件客户端。
- `cloud` 是默认关闭的只读云代理，不能绕开现场执行流程；`tray` 负责 Windows 桌面交互。
- 当前已有 `*DTO` 与 `*Request` 两种输入命名：查询/传输对象沿用 DTO，新增动作参数沿用 Request；不批量改名既有接口类型。
- 大型类逐个按职责拆分。优先候选：任务查询与执行、文件目录与元数据解析、监控采集与状态同步、托盘菜单与服务管理。拆分需先固定事务、锁、幂等和错误边界，独立验证。

## 注入与安全

- 新增必需依赖使用 `final` 字段和构造器注入；不能为迁就单元测试将生产安全依赖声明为可选。
- JWT 的账号查询、持久化会话服务，以及任务/控制服务的设备身份核验必须存在；缺失时拒绝启动，存储不可用时拒绝认证。
- 真正可选的组件需注明 profile、禁用时的行为和安全边界；现存其他可选注入按所属业务逐步整理。
- 测试显式提供 Mock 或测试实现，禁止通过删校验分支、允许 null 依赖来通过测试。
- 密码、密码哈希、JWT、bootstrap token、设备 API Key、派发确认凭据和内部存储定位字段不得进入对象日志。使用 `@ToString.Exclude` 或明确脱敏实现；JSON 的 WRITE_ONLY 不能替代日志脱敏。
- 本轮新增日志回归验证实际 `toString()` 输出；现有序列化回归继续验证对外 JSON。

## 配置与代码生成

- 公共 profile、测试资源和 Maven Wrapper 脚本纳入版本管理；真实秘密从环境变量或被忽略的本机文件读取。
- `application-dev.yaml` 可选导入仓库根 `config/application-dev.local.yaml`。本机已有四项数据库/缓存/存储凭据已迁到该文件；不要将其加入 Git。
- 新克隆工程设置 `MYSQL_PASSWORD`、`REDIS_PASSWORD`、`RUSTFS_ACCESS_KEY`、`RUSTFS_SECRET_KEY`；JWT 和管理员密钥按部署环境配置 `JWT_SECRET_KEY`、`ADMIN_SECRET_KEY`。
- 本机配置使用 `local.credentials.mysql-password`、`redis-password`、`rustfs-access-key`、`rustfs-secret-key` 四个带 `local.credentials.` 前缀的键；对应环境变量优先。
- `application-prod.yaml` 作为环境变量模板维护，不填写真实秘密。新增测试资源先确认是合成数据；生成结果写入 `target/`。
- 历史提交中的秘密不会随源码清理消失。若旧凭据仍有效，应另行安排轮换；本轮没有修改真实数据库密码。

`MyBatisPlusGenerator` 仅属于测试工具，运行前需在 IDE 运行配置或当前进程环境中设置：

| 环境变量 | 内容 |
| --- | --- |
| `GENERATOR_DATABASE_URL` | 用于结构读取的 JDBC URL，不附带密码 |
| `GENERATOR_DATABASE_USERNAME` | 结构读取账号 |
| `GENERATOR_DATABASE_PASSWORD` | 账号密码 |
| `GENERATOR_TABLES` | 逗号分隔的完整表名，例如 `farm_printer,farm_print_job` |

在 IDE 中手动运行该类。缺配置立即失败；只输出到 `target/codegen/java` 和 `target/codegen/resources/mapper`，目录已存在时拒绝继续。结果需人工审查后合入源码，不启用覆盖选项。生成器及 FreeMarker 依赖限定 test 作用域；测试不会运行生成器 main 方法。

## 格式与检查

- `.editorconfig` 定义 UTF-8、Java 四空格、YAML 两空格、末尾换行及脚本换行约定。
- 新增或修改 Java 使用显式导入；使用 import 代替业务代码中不必要的全限定类型名。沿用所在文件风格，避免整仓格式化。
- 当前检查入口执行改动的空白检查和 Maven 测试，不宣称已覆盖完整阿里巴巴规范或自动重排所有历史 Java 文件。

```powershell
# PowerShell 7，仓库根目录；默认执行全部 Maven 测试
pwsh -File scripts/check-code.ps1

# 仅检查改动空白并编译，不代表测试通过
pwsh -File scripts/check-code.ps1 -SkipTests

# 使用固定 Maven 版本；系统需提供 Java 25
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests
```

检查脚本不安装、启动或停止现场服务，不执行迁移或连接真实设备。它检查待提交的文本改动与新文件；暂存检查仍需配合 `git diff --cached --check` 和实际暂存内容审查。
