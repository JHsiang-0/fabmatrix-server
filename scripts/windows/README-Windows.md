# FabMatrix Server Windows Local Edition

正式构建入口是 `scripts/windows/build-installer.ps1`。它使用 Java 25 `jpackage` 生成后端和纯 JDK 托盘控制器的共享 runtime，再使用 Inno Setup 生成 `FabMatrixServer-Setup.exe`；WinSW `v2.12.0` 仅负责承载 Windows 服务。

## 安装、升级和数据布局

- 默认程序目录：`{autopf}\FabMatrix`（通常为 `C:\Program Files\FabMatrix`）
- 安装盘符为系统盘时，数据根：`{commonappdata}\FabMatrix`（通常为 `C:\ProgramData\FabMatrix`）
- 安装盘符不是系统盘时，数据根：`{app}\data`
- 两种布局都包含：`config\application.yml`、`database\farm.db`、`files`、`logs`、`backups`
- 默认监听：`0.0.0.0:8080`；Profile：`local`；后台 scheduler：关闭；Swagger/OpenAPI：关闭
- 固定 Windows 服务名：`FabMatrixServer`

安装器在复制新文件前会记录已有 `Farm`/`FabMatrixServer` 服务的 StartType 和是否正在运行，再正常停止服务和旧/新托盘进程。升级完成后恢复原启动类型和运行状态；全新安装才使用安装选项决定 Automatic/Manual 和是否立即启动。旧 `Farm` 服务注册可被清理，但任何业务数据目录都不会删除。升级、卸载均保留数据根。卸载前只关闭 `FabMatrixTray`/`FarmTray`，随后移除程序文件、服务注册和本安装器创建的私有 TCP 防火墙规则，不递归删除 `config`、`database`、`files`、`logs` 或 `backups`。

从旧版升级时，在旧服务已正常停止且新数据根尚未初始化的前提下，安装脚本检测 `C:\ProgramData\Farm`：先在旧根的 `backups\pre-fabmatrix-migration-*` 完整保留迁移源，再将 `data\farm.db` 映射到 `database\farm.db`、`uploads` 映射到 `files`、`config` 映射到 `config`。上一版 `Farm-Configure.ps1` 生成的配置没有 `spring.datasource.url`，迁移器会主动补入 `${FARM_DATA_DIR}/database/farm.db`，并把 files/logs 改为同一占位，同时保留原端口、CORS 和密钥。新根已有数据库、配置或文件时绝不覆盖；迁移或服务注册失败会中止安装、保留旧数据和迁移备份，并尽力恢复仍存在的原服务启动类型/运行状态。

安装器只为 Windows Firewall 的 `Private` profile 创建与端口一致的 TCP 入站规则，不开放 `Public` profile。ACL 会先清除 Users、Authenticated Users、Everyone 等旧显式 ACE，再将 `config`、`database` 和 `backups` 限制为 SYSTEM/Administrators 完全控制；`files`、`logs` 可供 Users 只读，但不可写。

## 托盘控制器

开始菜单快捷方式指向 `FabMatrixTray.exe`，安装器默认勾选（可取消）登录自启动和安装后启动。托盘以普通权限运行，使用 PowerShell `-EncodedCommand` 和短时 UAC helper 可靠处理含空格/单引号的安装路径，执行服务启动、正常停止、重启和管理员配置编辑；它轮询 SCM 的 `FabMatrixServer` 状态，并通过 `http://127.0.0.1:8080/actuator/health` 判定就绪。托盘支持刷新、状态/健康显示、复制局域网或本机地址、打开 `files`/`logs`、管理员编辑配置和退出。`backups` 为管理员私有目录，不由普通权限托盘直接打开。

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

在仓库根目录执行：

```powershell
.\scripts\windows\test-installer-contract.ps1
.\scripts\windows\build-installer.ps1                 # 默认完整安装包
.\scripts\windows\build-installer.ps1 -Mode AppImage  # 只生成 jpackage app-image
.\scripts\windows\build-installer.ps1 -Mode Payload   # 只生成 Inno payload
```

脚本要求 Java 25 JDK（含 `jpackage`）、Maven 或 `mvnw.cmd` 和 Inno Setup 6。WinSW 下载使用固定 URL 和 SHA-256 校验。完整构建默认运行后端测试；`-SkipTests` 只适合已单独验证后的构建。安装包不包含前端，前端需要单独部署。

后端 jpackage 主 launcher 使用 console 模式，使 WinSW 可发送正常停止信号；未使用 `-Xrs`，WinSW `stoptimeout=30sec` 大于 Spring graceful shutdown 的 20 秒。正式发布仍需在干净 Windows 安装态核对 WinSW 日志，确认停止/升级期间 graceful shutdown 完成且没有强制终止。

旧入口 `scripts\build-local-installer.ps1` 仅作为兼容委托，所有正式构建行为以 `scripts\windows\build-installer.ps1` 为准。
