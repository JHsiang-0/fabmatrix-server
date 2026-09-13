# FabMatrix Server Windows Local Edition

正式构建入口是 `scripts/windows/build-installer.ps1`。它使用 Java 25 `jpackage` 生成后端和纯 JDK 托盘控制器的共享 runtime，再使用 Inno Setup 生成 `FabMatrixServer-Setup.exe`；WinSW `v2.12.0` 仅负责承载 Windows 服务。

## 安装、升级和数据布局

- 默认程序目录：`{autopf}\FabMatrix`（通常为 `C:\Program Files\FabMatrix`）
- 安装盘符为系统盘时，数据根：`{commonappdata}\FabMatrix`（通常为 `C:\ProgramData\FabMatrix`）
- 安装盘符不是系统盘时，数据根：`{app}\data`
- 两种布局都包含：`config\application.yml`、`database\farm.db`、`files`、`logs`、`backups`
- 新安装默认监听：`0.0.0.0:28081`；Profile：`local`；后台 scheduler：关闭；Swagger/OpenAPI：关闭。升级时保留已有配置端口。
- 服务安装阶段日志保存在 `%ProgramData%\FabMatrix\installer\service-install.log`，与业务数据 ACL 隔离；覆盖安装失败时，提示框会直接显示该路径，且不会继续启动托盘。
- 覆盖安装会在读取旧配置前修复历史版本可能遗留的异常 ACL，仅处理 `config`、`database`、`backups`、`files`、`logs` 五个固定目录：接管所有权并重置历史显式拒绝后恢复 Administrators/SYSTEM 访问，不改写文件内容；任何子项修复失败都会中止，安装后仍按正式策略重新收紧权限。
- 固定 Windows 服务名：`FabMatrixServer`

安装器在复制新文件前会记录已有 `Farm`/`FabMatrixServer` 服务的 StartType 和是否正在运行，再正常停止服务和旧/新托盘进程。升级完成后恢复原启动类型和运行状态；全新安装才使用安装选项决定 Automatic/Manual 和是否立即启动。旧 `Farm` 服务注册可被清理，但任何业务数据目录都不会删除。卸载前只关闭 `FabMatrixTray`/`FarmTray`，随后移除程序文件、服务注册和本安装器创建的私有 TCP 防火墙规则；普通卸载保留完整业务数据根，不递归删除 `config`、`database`、`files`、`logs` 或 `backups`。

卸载有两种模式。交互式卸载先选择普通卸载或完全卸载：普通卸载保留当前 DataRoot、旧版 `%ProgramData%\Farm` 和 `%ProgramData%\FabMatrix` 安装诊断；选择完全卸载后还必须完成默认聚焦“否”的第二次不可逆确认，确认前可取消并退出。完全卸载会在服务和托盘停止后，通过 `purge-local-data.ps1` 删除当前 DataRoot、经 `data\farm.db`/非空 `config`/非空 `uploads` 识别的旧版 Farm 根，以及与当前根去重后的 FabMatrix 项目诊断残留，包括账号、数据库、任务、文件、配置、日志和备份；删除前请先备份，删除后重装不会再次迁回旧账号或旧端口。静默卸载默认绝不清理数据；命令行完全卸载必须显式使用 `/PURGEDATA=CONFIRM`，不能仅使用 `/SILENT`。系统盘安装的 DataRoot 是 `%ProgramData%\FabMatrix`，非系统盘安装的是 `{app}\data`。Inno 在 `usUninstall` 阶段只同步执行一次清理脚本并检查退出码，失败会中止卸载、记录并在交互模式提示各残留位置，静默模式只记录错误并返回失败。每个目标必须精确匹配安装盘、`%ProgramData%\Farm` 或 `%ProgramData%\FabMatrix` 策略；脚本拒绝空路径、相对路径、驱动器根、安装目录、越界路径以及目标自身、祖先或后代的 reparse point，并以逐节点复检、LiteralPath、非递归后序方式删除，不跟随 symlink/junction。

从旧版升级或卸载后重新安装时，在旧服务已正常停止且新数据根尚未初始化的前提下，安装脚本检测 `C:\ProgramData\Farm`：先在旧根的 `backups\pre-fabmatrix-migration-*` 完整保留迁移源，再将 `data\farm.db` 映射到 `database\farm.db`、`uploads` 映射到 `files`、`config` 映射到 `config`。迁移器通过原子结构化结果向安装脚本确认本次是否真实迁移；只有服务升级或真实 legacy migration 才会把旧纯数字默认 `server.port: 8080` 改为安装器 requested default `28081`，其他合法自定义端口保持不变，显式 `SERVER_PORT` 始终优先，普通 fresh config 的 8080 不会被无条件改写。上一版 `Farm-Configure.ps1` 生成的配置没有 `spring.datasource.url` 时，迁移器会主动补入 `${FARM_DATA_DIR}/database/farm.db`，并把 files/logs 改为同一占位，同时保留 CORS 和密钥。新根已有数据库、配置或文件时绝不覆盖；迁移或服务注册失败会中止安装、保留旧数据和迁移备份，并尽力恢复仍存在的原服务启动类型/运行状态。

安装器只解析一次实际端口，并将同一值写入 WinSW 服务级 `SERVER_PORT`、Windows Firewall `Private` TCP 入站规则及 `{app}\service-state\effective-port.txt`。该元数据只包含端口，显式授予 Users 读取/执行但不授予写入；不开放 `Public` profile，也不放宽业务数据 ACL。若组策略或终端防护阻止自动创建防火墙规则，服务安装仍会完成并在安装日志记录 `WARNING`，管理员需手动开放实际端口后局域网客户端才能访问。ACL 会先清除 Users、Authenticated Users、Everyone 等旧显式 ACE，再将 `config`、`database` 和 `backups` 限制为 SYSTEM/Administrators 完全控制；`files`、`logs` 可供 Users 只读，但不可写。升级安装请求默认端口 `28081` 时，若 Local 配置中的纯数字 `server.port` 仍为旧版默认 `8080`，安装器会先以原子方式迁移配置为 `28081`，再由同一 effective-port 流程同步服务、防火墙和托盘元数据；合法自定义端口（例如 `29090`）保持不变，显式 `SERVER_PORT` 始终优先。修改端口后必须重新运行安装器/服务注册流程，单独编辑配置并重启不会覆盖服务级 `SERVER_PORT`。

## 托盘控制器

开始菜单快捷方式指向 `FabMatrixTray.exe`，安装器默认勾选（可取消）登录自启动和安装后启动。托盘以普通权限运行，使用 PowerShell `-EncodedCommand` 和短时 UAC helper 可靠处理含空格/单引号的安装路径，执行服务启动、正常停止、重启和管理员配置编辑；它优先读取普通用户可读的 `{app}\service-state\effective-port.txt`，并通过 `http://127.0.0.1:<安装时生效端口>/actuator/health` 判定就绪。元数据缺失或无效时才依次回退托盘进程 `SERVER_PORT`、可读的 `config\application.yml` 和 `28081`，配置解析兼容纯数字与 `${SERVER_PORT:旧值}`。托盘支持刷新、状态/健康显示、复制局域网或本机地址、打开 `files`/`logs`、管理员编辑配置和退出。`config`、`database`、`backups` 仍为管理员私有目录，不因托盘读取端口而放宽。

“退出托盘（不停止服务）”只退出托盘进程，不会停止后端服务。没有可用 `SystemTray` 时会友好退出并保持服务不变。托盘不调用任何打印机控制 API。

## 配置和备份恢复

安装时由 `service\Farm-Configure.ps1` 生成目标机配置，并在目标机随机生成 JWT 和管理员密钥；密钥不写入构建目录或 Git。SQLite、files 和日志路径使用 `${FARM_DATA_DIR}` 占位，由 WinSW 在目标机注入当前数据根，因此跨盘恢复不会保留源机器绝对路径。编辑配置后请使用托盘的“管理员编辑配置”，或以管理员身份编辑后重启服务。

备份/恢复必须先停止 `FabMatrixServer`，脚本不会自动停止服务，以避免 SQLite 热备份不一致。PowerShell 示例：

```powershell
powershell -ExecutionPolicy Bypass -File maintenance\local-backup.ps1 -DataRoot 'C:\ProgramData\FabMatrix'
powershell -ExecutionPolicy Bypass -File maintenance\local-restore.ps1 `
  -BackupDir 'C:\ProgramData\FabMatrix\backups\farm-local-20260912-120000' `
  -DataRoot 'C:\ProgramData\FabMatrix' -Confirm RESTORE
```

安装包把脚本和共享维护 helper 安装到 `{app}\maintenance`。备份至少包含 `database\farm.db`、`files` 和 `config\application.yml`，并把配置中的本机数据根规范为 `${FARM_DATA_DIR}`。恢复先复制到同卷 staging，再把当前 `config`/`database`/`files` 整体移动到 `backups\pre-restore-*`，最后整体换入 staging，并重新施加 config/database/backups 私有、files/logs 只读 ACL；ACL 失败也会触发尽力回滚。该流程不会留下旧 WAL/SHM 或孤儿文件，同时保留恢复前完整数据。Linux 脚本保持相同整体替换语义，但不模拟 Windows ACL，必须另行维持合适的 owner/mode。恢复后应重新启动服务并检查 `/actuator/health`、文件列表和任务列表。

## 构建和回归检查

### 可替换品牌资源

构建使用固定资源目录 `scripts\windows\assets\`：

- `installer.ico`：Inno Setup 安装器图标。
- `server.ico`：FabMatrixServer 主 launcher 图标。
- `tray.ico`：FabMatrixTray 附加 launcher 图标。
- `tray.png`：运行时托盘图标，作为 `FabMatrixTray.jar` 的 classpath 资源。

三个 ICO 为包含 16、24、32、48、64、128、256 像素图像的透明 Windows ICO；PNG 必须是透明 PNG。可直接替换这四个独立文件，不要求文件内容相同；替换后必须重新运行 jpackage/Inno 构建，已生成的 EXE/JAR 不会自动更新。默认资源由前端公共品牌图标生成，源文件为 `D:\WorkSpace\Vue\fabmatrix-client\public\icon.png`。

### 输出清理边界

默认构建不会清理输出目录。需要清理旧发布候选时显式使用：

```powershell
.\scripts\windows\build-installer.ps1 -CleanOutput
```

`OutputDir` 只允许使用当前仓库 `dist\` 的严格子目录；默认值 `dist\windows-installer` 可直接使用，但不能把 `OutputDir` 设为 `dist` 本身、仓库外目录、UNC/设备路径、短路径别名、映射盘或 SUBST 盘符。输出目录自身及从文件系统根开始的所有已存在祖先也不能是重解析点。需要把安装器发布到仓库外位置时，应先在 `dist` 子目录完成构建和校验，再单独复制最终安装器，不要把外部发布目录直接传给构建脚本。

`-CleanOutput` 只删除解析后的 `OutputDir` 内已知生成项：`work`、`precommit-check`、当前或旧的 `FabMatrixServer-Setup*.exe`/`Farm-Setup*.exe`、`checksums.txt`、`release-info.txt`、输出副本 `README-Windows.md` 和顶层 `acceptance-*.log`；不会删除 `OutputDir` 本身或其中其他未知文件。`-NoClean` 仍只保留原语义：跳过 Maven `clean`，不改变 `-CleanOutput` 的显式行为。

在仓库根目录执行：

```powershell
.\scripts\windows\test-installer-contract.ps1
.\scripts\windows\build-installer.ps1                 # 默认完整安装包
.\scripts\windows\build-installer.ps1 -CleanOutput    # 清理已知旧产物后构建
.\scripts\windows\build-installer.ps1 -Mode AppImage  # 只生成 jpackage app-image
.\scripts\windows\build-installer.ps1 -Mode Payload   # 只生成 Inno payload
```

脚本要求 Java 25 JDK（含 `jpackage`）、Maven 或 `mvnw.cmd` 和 Inno Setup 6。WinSW 下载使用固定 URL 和 SHA-256 校验。完整构建默认运行后端测试；`-SkipTests` 只适合已单独验证后的构建。安装包不包含前端，前端需要单独部署。

后端 jpackage 主 launcher 使用 console 模式，使 WinSW 可发送正常停止信号；未使用 `-Xrs`，WinSW `stoptimeout=30sec` 大于 Spring graceful shutdown 的 20 秒。正式发布仍需在干净 Windows 安装态核对 WinSW 日志，确认停止/升级期间 graceful shutdown 完成且没有强制终止。

旧入口 `scripts\build-local-installer.ps1` 仅作为兼容委托，所有正式构建行为以 `scripts\windows\build-installer.ps1` 为准。
