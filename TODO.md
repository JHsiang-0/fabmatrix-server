# Farm 开发 TODO v2.0

更新时间：2026-09-03
当前规格：`.kiro/specs/farm-v2/`
依据：`PROJECT.md`、`API_HANDOFF.md`、当前 Java 后端代码和已完成的 RRF 设备验收记录。

## 一、产品基线

Farm 是一个局域网内的本地打印农场服务端，多个客户端通过浏览器或客户端连接。账号由管理员在本地创建，不引入手机号注册、邮箱验证、多租户或公开互联网账号体系。

本版本必须遵循以下业务优先级：

1. **手动上传、手动选择打印机、手动启动**是默认主流程。
2. 单个任务由用户明确选择文件和打印机；上传到设备、确认安全条件、启动打印均有明确的用户操作。
3. **批量手动上传**是后续重点：用户一次选择多个文件和打印机，系统生成分配预览，用户确认后逐项执行，并展示每项成功或失败。
4. “自动分配”只表示用户主动发起的匹配建议或批量分配，不等于后台自动派单。
5. 后台自动派单不属于 v2 交付范围，v2 不提供入口和执行接口；相关能力延后到 v3 重新设计。
6. 监控设备状态和自动派单必须解耦。没有真实设备时可以只关闭调度，不能因为测试设备不存在而影响普通接口。

部署形态约定：

- v1 可以发布为 Docker Server Edition：Farm 后端、MySQL、Redis、RustFS 使用正式 Compose 编排。
- v2 增加 Windows Local Edition：SQLite + Windows 本地文件目录，不依赖 Docker、MySQL、Redis、RustFS。
- MySQL/Redis/RustFS 保留为 Server Edition；两种部署形态共用业务 API 和协议适配层。

业务术语约定：

| 术语 | 含义 | 是否默认开启 |
|---|---|---:|
| 单任务手动派单 | 用户选择一个文件和一台打印机并执行安全打印流程 | 是 |
| 批量手动上传 | 用户主动选择多文件/多打印机，预览匹配后确认逐项上传 | 否（功能完成后可用） |
| 用户发起的自动分配 | 用户点击按钮后，系统按策略生成批量匹配方案 | 否（需用户触发） |
| 后台自动派单 | 定时任务扫描队列并生成/执行分配 | v3 延后 |

## 二、当前基线与已知问题

- `farm-v1` 的基础接口、权限、任务安全流程、协议适配基础和 WebSocket 后端作为历史基础保留，详见 `.kiro/specs/farm-v1/`。
- RRF 真实设备验收目标为 `192.168.0.77`，Farm 打印机 ID 为 `564`；当前设备已回到空闲状态。`192.168.0.62` 仅为历史模拟/错误记录，不作为真实验收设备。
- 当前 dev 配置明确关闭 `farm.monitor.enabled` 和 `farm.scheduler.enabled`；旧 `farm.tasks.enabled` 不会误启动 v2 监控或后台派单。
- 当前 `JobSchedulerTask` 仍作为 v3 预留保留，但 v2 默认不会扫描或自动分配任务；任务创建和扫描状态均统一为 `QUEUED`。
- 当前任务模型是“一条 `PrintJob` 对应一个文件”，打印机可以在创建时为空；手动分配后绑定一台打印机。同一个文件打印到多台打印机必须创建多条任务。
- 2026-09-03 已备份并修复开发库中的 1 条历史幽灵绑定：任务 1 指向已不存在的打印机 289，现已解除绑定并置为 `RECONCILING`，审计记录保存在 `farm_binding_repair_audit`；未删除任务或自动重新派单。可复用脚本为 `scripts/repair-ghost-bindings.sql`。
- 打印机绑定变化已刷新缓存，监控每台设备处理前重新读取数据库；仍需真实白名单设备验收。
- RRF 适配器已解析文件位置、大小、剩余时间、取消/中止标志；`idle` 只有终态证据才完成，否则进入 `RECONCILING`。
- 控制接口已通过协议适配器执行并回写暂停、恢复、取消、急停的 Farm 状态；设备 HTTP 成功仍不等于物理动作已现场确认。
- 数据库已加入批量计划、原子绑定、创建幂等键和计划资源摘要；活动绑定表、全量并发验收和恢复验收仍是后续收口项。

## 三、执行路线

### P0：先稳定生命周期和运行边界

- [x] P0.1 固化手动优先的任务状态、派单模式和批量操作 API 契约。
- [x] P0.2 拆分 `farm.monitor.enabled` 与 `farm.scheduler.enabled`，两者默认关闭；增加监控打印机白名单，避免测试环境扫描全部设备。
- [x] P0.3 修复监控数据新鲜度：任务绑定以数据库最新值为准，补充缓存失效/刷新策略和设备不可达状态。
- [x] P0.4 完善 RRF/Klipper 任务生命周期：区分设备观测状态、任务期望状态和最终状态，不能把 RRF `idle` 直接当作完成。
- [x] P0.5 已加入创建幂等键、批量计划重复确认、资源摘要校验、Redis/进程内锁和数据库条件绑定；跨进程恢复现场验收仍需完成。
- [~] P0.6 已统一主要控制入口的安全校验、权限、状态回写、互斥锁和协议错误边界，并补充暂停/急停状态回归；真实设备确认和全量异常集成测试仍需完成。

### P1：完成默认的手动和批量业务

- [~] P1.1 后端单任务手动流程已具备并有 Service/协议测试；真实 `.77` 设备和前端端到端回归仍待完成。
- [x] P1.2 新增批量文件上传接口，支持多文件、单项校验、单项结果、请求内重复文件识别和失败重试提示。
- [x] P1.3 新增批量分配预览接口：用户选择文件和打印机后，系统只计算方案，不产生设备副作用。
- [~] P1.4 批量分配确认接口已实现并具备原子认领、锁/条件绑定/资源摘要校验；WebSocket/REST 一致性和跨进程恢复测试仍待补齐。
- [x] P1.5 批量操作支持“仅上传”“上传并排队”“上传并在安全确认后启动”三种明确动作，默认不直接启动；当前 `START_AFTER_CONFIRM` 返回已派发任务，由前端继续调用单项安全接口。
- [~] P1.6 `/home/codex/workspace/farm-ui` 已接入单任务安全确认/启动、批量上传取消/失败项重试、设备/文件选择、批量预览/确认、逐项结果和 WebSocket 序号断档恢复；批量逐项安全启动和浏览器真实端到端仍待完成。

### P1.7：v2 本地版与 v1 服务版部署

- [~] P1.7.1 已抽象文件存储接口，实现 SQLite + 本地文件存储的 Local profile，并完成关键读写集成测试；已提供 Java 25 `jpackage` Windows 构建脚本，仍需在 Windows 发布机生成并验收 `.exe`。
- [x] P1.7.2 已将 Redis 缓存/锁切换为 Local profile 进程内实现，同时保留 Server Edition 的 Redis 实现。
- [~] P1.7.3 已有 SQLite 初始表结构、幂等索引和可重复初始化，并完成关键 SQLite Mapper 方言测试；已提供带恢复前保留措施的备份/恢复脚本，MySQL 数据导入边界和正式恢复演练仍待完成。
- [x] P1.7.4 已提供 v1 Server Edition 的 Farm、MySQL、Redis、RustFS 正式 Docker Compose 编排、密钥注入、健康检查和数据卷说明。
- [~] P1.7.5 已补充两种形态的启动、数据目录检查、备份恢复说明和 Server Edition 镜像构建记录；Windows 安装/卸载和正式发布验证仍待完成。
- [~] P1.7.6 已完成 FabMatrix Server Windows Local Edition 正式安装包脚本、按安装盘符选择数据根、真实旧配置迁移归一化、升级状态恢复、安装失败传播、私有网络防火墙、显式 ACL 清理、独立托盘和带 ACL 回滚的可移植恢复，并构建校验安装包；干净 Windows 机器上的 C/非 C 安装升级卸载、graceful shutdown 日志及数据保留验收仍待完成。
- [x] P1.7.7 Windows Local Edition 安装器、主程序、托盘启动器和运行时托盘图标支持固定资源目录替换；已使用 `server_logo.png` 生成并替换全套 Windows 图标，安全清理旧产物后在本机使用 Inno Setup 6 完成 Full 构建，392 tests passed，发布候选的体积和 SHA-256 以输出目录中的 `release-info.txt`、`checksums.txt` 为准。
- [x] P1.7.8 修复 Windows Local Edition 托盘中文方块：使用 Swing `JPopupMenu`/`JMenuItem` 自绘菜单规避 Windows AWT native peer 忽略 Java 字体，保留中文字体完整性检查和英文回退；右键菜单由可释放的 `JWindow` anchor 承载并按当前显示器可用边界定位，外部点击或焦点离开时关闭且不受 popup 自身焦点切换影响；jpackage 保留完整 JDK runtime，源码、打包 launcher、Swing 菜单模型和 runtime 模块均有契约自检。已完成 Full 安装包构建、契约检查及 392 项 Maven 测试。
- [x] P1.7.9 将新部署默认 HTTP/WebSocket 端口统一为 `28081`，覆盖后端 profile、Docker、Windows 安装器/防火墙/托盘和前端首次连接；升级安装保留已有端口，Windows 服务、防火墙和普通权限托盘共用安装时解析的实际端口，显式环境变量及客户端已保存地址继续优先；默认值、覆盖、只读端口元数据、ACL 和安装器契约回归均已通过。
- [x] P1.7.10 修复 Windows Local Edition 覆盖安装的服务注册竞态：释放服务控制句柄、等待旧服务从 SCM 完全删除并有界重试 WinSW 注册；新增持久化分阶段安装日志，失败时不再启动托盘。
- [x] P1.7.11 修复旧版 Windows 数据 ACL 阻断覆盖安装：读取配置前只恢复五个固定数据目录的管理员/SYSTEM 访问权，完成安装后重新应用正式私有/只读 ACL。
- [x] P1.7.12 修复旧数据 ACL 恢复误报成功：递归重置历史显式拒绝后再授权，并禁止 ACL 恢复阶段跳过失败子项。
- [x] P1.7.13 防火墙规则自动创建失败不再回滚已注册服务，并将安装诊断日志移出受保护业务数据目录。
- [x] P1.7.14 修复 Windows Local Edition 既有服务日志 ACL：五个固定数据目录树的既有文件/目录获得直接 SYSTEM/Administrators 完全控制，`files/logs` 保留 Users 只读及未来继承策略，覆盖 WinSW stdout/stderr 日志并补充 installer contract 回归。
- [x] P1.7.15 修复 Windows Local Edition 服务注册后 Java 子进程因完整 `logging.file.name` 被重复拼接而退出的问题：日志配置直接使用完整文件路径并保留默认/Server Edition 兼容回退，移除 WinSW `LOG_PATH` 注入；在固定数据 ACL 递归变更前拒绝根及后代 reparse point，`takeown` 使用 `/SKIPSL`，并补充日志启动探针、路径合同、junction 外部 sentinel 和继承语义回归。`mvnw test` 392 项、Windows installer contract、打包 Local health/log 探针和 Inno Setup Full 构建均通过；安装包 `dist/windows-installer/FabMatrixServer-Setup.exe` 为 119597612 bytes，SHA-256 为 `CEFA62849F475B4447C97FF496226C72C760F2BA71169ED282CAAF39324AD43A`。
- [x] P1.7.16 修复 Windows Local Edition 托盘服务控制的位置参数绑定和 UAC 错误回传：服务控制脚本改为安全的位置参数调用，提升进程使用当前用户临时目录中的随机结果文件回传成功/真实错误，抑制 ProgressRecord/CLIXML 并在 `finally` 清理；升级安装将纯数字旧默认端口 `8080` 原子迁移为请求的默认 `28081`，保留合法自定义端口和显式 `SERVER_PORT` 优先级，并统一同步 WinSW、防火墙和托盘元数据；已补充参数绑定、CLIXML 清洗/临时文件清理、端口迁移和打包托盘自测合同。`mvnw.cmd test` 392 项、Windows installer contract 和 Full installer build 均通过；安装包 `dist/windows-installer/FabMatrixServer-Setup.exe` 为 119602125 bytes，SHA-256 为 `B6CA73258266F993107D2C307C57173A9CBF02985E71E69BA4F525D6E0FB01BD`。
- [x] P1.7.17 修复 Windows Local Edition 旧 SQLite 升级启动顺序：`local-schema.sql` 不再在旧 `farm_print_job` 补齐 TC-007 演进列前创建 `retry/root` 唯一索引，索引统一由 `LocalSchemaMigration` 在补列和回填后幂等创建；审计初始 Local schema 到当前演进字段/索引并修正现有 legacy 假覆盖测试，新增真实 pre-TC007 bootstrap 顺序测试和临时 SQLite 文件的 Local profile 启动/HTTP health 集成探针，保留旧任务及文件/打印机关联。`mvnw.cmd test` 393 项、Windows installer contract 和 Full installer build 均通过。安装包 `dist/windows-installer/FabMatrixServer-Setup.exe` 为 119600999 bytes，SHA-256 为 `9CDD4A4B22140B79CFB3677D580A7EC36E1880369357C587B32A43AD13A9A680`。
- [x] P1.7.18 修复 Windows Local Edition 升级安装期间旧服务/托盘/runtime 进程未完全退出造成的 `jimage.dll` 文件占用：复制前保存并保护升级状态，按 `{app}` 过滤进程树并有界等待/清理，阻断 WinSW 重启竞态，预检失败则在复制前中止并尽力恢复；Inno 与安装器契约护栏已同步。`mvnw.cmd test` 393 项、Windows installer contract 和完整 Windows 安装器构建均通过；安装包 `dist/windows-installer/FabMatrixServer-Setup.exe` 为 119616592 bytes，SHA-256 为 `B1CFFFBF7DCB3BE1DF7E8F1A20053986A16F78FFFB09A0974F66FB9334946193`。

- [x] P1.7.19 / T16.19 为 Windows Local Edition 增加默认保留数据卸载与显式完全卸载双模式：普通/静默卸载删除服务、托盘/程序和防火墙规则但保留业务数据；交互式完全卸载要求清晰模式选择和默认“否”的第二次不可逆确认，命令行仅接受 `/PURGEDATA=CONFIRM`。主审修复后改由 Inno `usUninstall` 回调单次同步调用脚本、校验进程退出码并中止失败卸载；清理脚本完整检查驱动器根、InstallDir/DataRoot 及已存在祖先的 reparse point，以逐节点复检、LiteralPath、非递归后序方式删除受控 DataRoot。隔离自测覆盖不同 cwd 及 DataRoot 根、InstallDir 根/祖先和后代 junction，contract 还执行 ISCC 卸载阶段编译；`mvnw.cmd test` 393 项、Windows installer contract 和完整 installer build 已通过。

### v3 预留：后台自动派单与设备状态同步优化（不计入 v2 验收）

- 后台自动派单需要重新定义授权、计划确认、审计、失败重试和并发占用，v2 不实现。
- v2 只保证调度器默认关闭且不会因旧配置缺省值误启动；现有 `JobSchedulerTask` 作为后续设计参考保留。
- v3 增加“命令请求”和“设备确认”两个阶段：暂停、恢复、取消、急停的 HTTP 成功只表示请求已发送，必须由设备状态或明确设备回执确认后，才释放打印机绑定或写入最终任务状态。
- v3 重点修复长时间 `G4`/设备阻塞时的取消收敛：取消请求期间保留绑定，进入可恢复中间态；设备确认停止后再解除绑定，超时进入 `RECONCILING`，禁止出现“Farm 已取消但设备仍 processing”的隐性占用。
- v3 引入自适应监控：空闲设备低频轮询，打印中设备高频轮询，离线设备指数退避；控制动作后短时加密轮询，状态无变化不重复写 MySQL，WebSocket 只推送变化和必要进度。
- v3 设计可选的设备事件上报适配器；只有固件或网关实际提供可靠事件通道时才启用，轮询仍作为心跳、断线检测和最终一致性兜底，不能假设所有 RRF 设备都支持主动上报。
- v3 需要增加命令审计/幂等记录、确认超时、跨进程恢复、设备连接压力和状态延迟指标，并用多设备压测确定轮询上限。

### P2：运营和健壮性

- [ ] P2.1 增加操作审计记录：谁在何时对哪台设备、哪个文件、哪个任务执行了什么动作。
- [ ] P2.2 增加设备心跳、最后在线时间、错误码、重连退避和监控告警。
- [x] P2.3 WebSocket 已具备版本、eventId/sequence、JWT 鉴权、连接上限、断线重连和 REST 快照兜底；按设备订阅暂不纳入本地农场 v2。
- [ ] P2.4 增加真实数据库迁移、接口权限集成测试、批量接口测试和 RRF/Klipper 协议回归测试。
- [~] P2.5 已按当前 Controller、`SecurityConfig` 和 OpenAPI 注解收口 `API_HANDOFF.md`，移除过期历史 API 文档；WebSocket、开发 Mock 路由、Local 存储入口和 v3 自动派单边界已明确。Swagger/OpenAPI 与运维文档仍需单独完成最终发布复核。

## 四、目标 API 契约（新增部分）

现有单任务 API 继续兼容；批量上传、预览和确认接口已进入代码，但确认的资源版本并发增强仍在 P0.5/T4。

### 批量文件上传

`POST /api/v1/print-files/batch-upload`，`multipart/form-data`，字段 `files` 可重复，可选 `parentId` 指定虚拟目录。返回每个文件的成功/失败结果，不因单个文件失败而隐藏其他结果。

### 批量分配预览

`POST /api/v1/print-jobs/batch/preview`

```json
{
  "fileIds": [45, 46],
  "printerIds": [564],
  "strategy": "ONE_TO_ONE",
  "action": "UPLOAD_ONLY"
}
```

支持的策略至少包括 `ONE_TO_ONE`、`ROUND_ROBIN`、`AUTO_MATCH`。预览只返回 `planId`、匹配项、冲突、不可用原因、过期时间和确认令牌摘要，不创建任务、不上传设备、不启动打印。

### 批量分配确认

`POST /api/v1/print-jobs/batch/confirm`

确认后才创建或绑定任务。返回 `items`，每项包含 `fileId`、`printerId`、`jobId`、`action`、`status`、`message` 和可重试标识。默认动作是 `UPLOAD_ONLY` 或 `QUEUE`，启动必须经过安全确认。

后台自动派单不发布 v2 接口；v3 如重新启用，应复用批量计划模型，不能另起一套绕过确认的状态机。

部署差异只存在于基础设施，不新增前端业务接口：Local Edition 使用 SQLite/本地文件，Server Edition 使用 MySQL/Redis/RustFS。

## 五、验收原则

- 默认启动后不应有后台任务自动占用打印机。
- 用户不点击确认时，批量预览不能改变数据库或真实设备状态。
- 同一个计划重复确认不会创建重复任务或重复上传。
- 一台设备离线只影响对应项目，不阻塞其他批量项和普通接口。
- RRF 的 `idle`、`processing`、`paused`、`halted` 与 Farm 任务最终状态有明确映射和证据来源。
- 所有新增敏感接口同时具备路由鉴权、服务层权限校验、参数校验、统一错误响应和日志追踪。
- 每完成一个 Task：运行针对性测试，更新 `.kiro/specs/farm-v2/tasks.md`、`TODO.md` 和接口文档，再创建普通本地提交；阶段验收完成后再统一使用 GPG 签名提交。

## 六、执行顺序

当前继续按照 `.kiro/specs/farm-v2/tasks.md` 中未完成的 `[~]`、`[ ]` 任务推进：优先完成真实设备监控/生命周期和跨进程恢复验收，再完成前端浏览器端到端联调、Windows Local Edition 安装恢复演练及 Server Edition 正式发布验收。后台自动派单已移出 v2，另列为 v3 规划。
