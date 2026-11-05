# 扫码配对版本打包记录

2026-11-05 完成重新打包，业务源码与上一轮测试版本一致。本记录仅覆盖构建、包内容与校验，不表示已安装或完成手机真机扫码。

| 组件 | 构建源码提交 | 产物（相对对应仓库） | 大小（字节） |
| --- | --- | --- | ---: |
| Server 1.0.0 | a1683b7 | dist/windows-installer/FabMatrix-Server-Setup-1.0.0.exe | 122534751 |
| Client 1.0.0 | af2469a | release/FabMatrix-Client-Setup-1.0.0.exe | 128309180 |
| Cloud 0.1.0-SNAPSHOT | 5a435e6 | target/fabmatrix-cloud-0.1.0-SNAPSHOT.jar | 34830610 |
| Android 1.0.0+1（debug） | 9501c7e | build/app/outputs/flutter-apk/app-debug.apk | 222588701 |

## SHA-256

```text
Server 869A3945C01CF267827D1C5C9CB241446222418B78333F42C87D904B4AAFA45C
Client BE039BE8B54E4CA87D3211E6A5DE9A0355F856E53A384C807C0B4401EBD7798E
Cloud  023ED3C4E2C15F7136D97935113AAF3C33FF862F6AF8AF319543E7C5C9528326
APK    350C37BCD4F3FA27CA6C0261870457058141C29ECAF6C680C8D19A6A3F61FCF8
```

## 构建与核对

- Server：`pwsh -NoProfile -File scripts/windows/build-installer.ps1 -SkipTests -ClientRoot D:/WorkSpace/Vue/fabmatrix-client`。默认输出目录内的 Windows 安装器契约、隔离启动/健康探测及托盘检查通过；内置 Java 25 和 SQLite 驱动，payload JAR 与本次 Maven JAR 的 SHA-256 一致，扫码配对类已包含。
- Client：重新执行 `npm run build:desktop` 和正式 NSIS 打包；复用现有匹配 ICO，避免 PNG 转换工具的已知内存问题。ASAR 内 59 个 dist/electron 文件与新构建逐项一致，扫码入口、start/poll 接口已包含；正式产物扫描 54 个文件、0 个发现。
- Cloud：`mvnw.cmd package -DskipTests`，已确认 JAR 包含配对 Controller/Service 与 V18 迁移，未执行真实数据库迁移。
- Android：`flutter build apk --debug --no-pub --android-project-arg=kotlin.incremental=false`；确认 Dart 配对流程、原生 MobileScannerPlugin、CAMERA 权限和 APK v2 签名，支持 arm64-v8a、armeabi-v7a、x86_64。

本轮复用相同业务源码的既有验证：Server 905 项、Cloud 89 项与 26 项隔离集成、Client 457 项及完整发布门禁、手机 89 项（1 项真实服务测试跳过）。没有为了打包重复运行全量业务测试。安装器相关检查来自本次重新构建。

构建后现场 FabMatrixServer 仍为 Running，监听 PID 27364，健康状态 UP。未替换已安装的 Server/Client，未安装 APK，未部署 Cloud，未更改现有网关或操作打印机。

升级顺序：先部署 Cloud 并应用 V18，再更新 Server、Client 与手机 App，随后另行完成相机扫码和配对验收。功能与权限边界见 [云端连接](CLOUD_GATEWAY.md)。后续文档提交只登记产物，不改变以上业务源码或产物内容。
