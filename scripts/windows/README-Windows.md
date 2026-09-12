# FabMatrix Server Windows Local Edition

正式构建入口是 `scripts/windows/build-installer.ps1`。它使用 Java 25 `jpackage` 生成后端和纯 JDK 托盘控制器的共享 runtime，再使用 Inno Setup 生成 `FabMatrixServer-Setup.exe`；WinSW `v2.12.0` 仅负责承载 Windows 服务。

## 安装、升级和数据布局

- 默认程序目录：`{autopf}\FabMatrix`（通常为 `C:\Program Files\FabMatrix`）
- 安装盘符为系统盘时，数据根：`{commonappdata}\FabMatrix`（通常为 `C:\ProgramData\FabMatrix`）
- 安装盘符不是系统盘时，数据根：`{app}\data`
- 两种布局都包含：`config\application.yml`、`database\farm.db`、`files`、`logs`、`backups`
- 默认监听：`0.0.0.0:8080`；Profile：`local`；后台 scheduler：关闭；Swagger/OpenAPI：关闭
- 固定 Windows 服务名：`FabMatrixServer`

安装器在复制新文件前会正常停止旧 `Farm`/`FabMatrixServer` 服务和旧/新托盘进程；旧 `Farm` 服务注册可被清理，但任何业务数据目录都不会删除。升级、卸载均保留数据根。卸载只移除程序文件、服务注册和本安装器创建的私有 TCP 防火墙规则，不递归删除 `config`、`database`、`files`、`logs` 或 `backups`。

安装器只为 Windows Firewall 的 `Private` profile 创建与端口一致的 TCP 入站规则，不开放 `Public` profile。ACL 将 `config` 和 `database` 限制为 SYSTEM/Administrators 完全控制；`files`、`logs`、`backups` 可供 Users 只读，但不可写。

## 托盘控制器

开始菜单快捷方式指向 `FabMatrixTray.exe`，安装器也提供可选的登录自启动和安装后启动。托盘以普通权限运行，使用短时 UAC helper 执行服务启动、正常停止和重启；它轮询 SCM 的 `FabMatrixServer` 状态，并通过 `http://127.0.0.1:8080/actuator/health` 判定就绪。托盘支持刷新、状态/健康显示、复制局域网或本机地址、打开 `files`/`logs`/`backups`、管理员编辑配置和退出。

“退出托盘（不停止服务）”只退出托盘进程，不会停止后端服务。没有可用 `SystemTray` 时会友好退出并保持服务不变。托盘不调用任何打印机控制 API。

## 配置和备份恢复

安装时由 `service\Farm-Configure.ps1` 生成目标机配置，并在目标机随机生成 JWT 和管理员密钥；密钥不写入构建目录或 Git。编辑配置后请使用托盘的“管理员编辑配置”，或以管理员身份编辑后重启服务。

备份/恢复必须先停止 `FabMatrixServer`，脚本不会自动停止服务，以避免 SQLite 热备份不一致。PowerShell 示例：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\local-backup.ps1 -DataRoot 'C:\ProgramData\FabMatrix'
powershell -ExecutionPolicy Bypass -File scripts\local-restore.ps1 `
  -BackupDir 'C:\ProgramData\FabMatrix\backups\farm-local-20260912-120000' `
  -DataRoot 'C:\ProgramData\FabMatrix' -Confirm RESTORE
```

备份至少包含 `database\farm.db`、`files` 和 `config\application.yml`。恢复前会把当前三者复制到数据根的 `backups\pre-restore-时间戳`，不会删除原数据；恢复后应重新启动服务并检查 `/actuator/health`、文件列表和任务列表。安装包内提供这些脚本和 `service` 控制脚本。

## 构建和回归检查

在仓库根目录执行：

```powershell
.\scripts\windows\test-installer-contract.ps1
.\scripts\windows\build-installer.ps1                 # 默认完整安装包
.\scripts\windows\build-installer.ps1 -Mode AppImage  # 只生成 jpackage app-image
.\scripts\windows\build-installer.ps1 -Mode Payload   # 只生成 Inno payload
```

脚本要求 Java 25 JDK（含 `jpackage`）、Maven 或 `mvnw.cmd` 和 Inno Setup 6。WinSW 下载使用固定 URL 和 SHA-256 校验。完整构建默认运行后端测试；`-SkipTests` 只适合已单独验证后的构建。安装包不包含前端，前端需要单独部署。

旧入口 `scripts\build-local-installer.ps1` 仅作为兼容委托，所有正式构建行为以 `scripts\windows\build-installer.ps1` 为准。
