# Farm Windows 安装包

本目录用于构建 Farm 后端 Windows EXE 安装程序。安装包采用 Inno Setup，应用本体由 Java 25 `jpackage` 生成并自带运行时；Windows 服务由 WinSW `v2.12.0` 包装。

## 默认安装行为

- 程序目录：`C:\Program Files\Farm`
- 数据目录：`C:\ProgramData\Farm`
- 配置：`C:\ProgramData\Farm\config\application.yml`
- 日志：`C:\ProgramData\Farm\logs`
- SQLite 数据库：`C:\ProgramData\Farm\data\farm.db`
- 本地上传文件：`C:\ProgramData\Farm\uploads`
- 默认端口：`8080`
- 默认 Profile：`local`
- 服务名：`Farm`
- 显示名称：`Farm 3D Printer Management`

默认 Local Edition 不需要 MySQL、Redis 或 RustFS，首次启动后通过 `/api/v1/auth/setup/status` 和 `/api/v1/auth/setup/admin` 创建管理员。安装包不包含前端，前端需要单独部署。

## 修改配置

安装时会随机生成 JWT 和管理员密钥；真实密钥只写入目标机的 ProgramData 配置，不写入安装器构建目录或 Git。编辑配置后重启服务：

```powershell
Restart-Service Farm
```

也可以使用随包提供的脚本生成 Server Edition 配置（需要自行提供 MySQL、Redis、RustFS）：

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Program Files\Farm\service\Farm-Configure.ps1" `
  -Edition Server -Force `
  -MysqlUrl 'jdbc:mysql://db-host:3306/farm' -MysqlUsername farm -MysqlPassword '...' `
  -RedisHost redis-host -RedisPassword '...' `
  -RustFsEndpoint 'http://rustfs-host:9000' -RustFsAccessKey '...' -RustFsSecretKey '...'
Restart-Service Farm
```

卸载程序会停止并删除服务和 Program Files 下的应用，但默认保留 `C:\ProgramData\Farm`，不会删除 SQLite、上传文件或外部数据库/Docker 数据卷。

## 构建

在仓库根目录执行：

```powershell
.\scripts\windows\build-installer.ps1
```

脚本要求 Java 25 JDK、Maven（或仓库内 `mvnw.cmd`）和 Inno Setup。WiX 未安装时使用 Inno Setup EXE；如果机器没有 Inno Setup，脚本会失败并提示安装方式。脚本会下载固定版本的 WinSW，并校验 SHA-256。
