# 打印历史导出任务

产品版本：`1.0.0` 准正式版维护增强；更新时间：2026-10-08
前端规格：`farm-v1-release-readiness` R100-16

## T0 需求与接口

- [x] 核对历史页面、权限、摘要 SQL、快照与时间字段，冻结业务接口与公共导出模块边界。
- [x] 明确仅平台终态、条数非件数、经过时长含暂停和无迁移范围。

## T1 实现

- [x] 独立 Controller/DTO/Service、有界 MySQL/SQLite 查询与服务层资源边界；SQLite 列表和导出统一兼容混合时间格式并保留纳秒精度，HTTP 时间输入严格秒级。
- [x] 通用 Excel/PDF renderer、OFL 中文字体、正确数据类型和 20 MiB 限制。
- [x] 前端已应用筛选、两种格式下载、认证/JSON 错误、并发与卸载清理，源码提交 `526df89`。
- [x] API、配置、项目与任务文档同步。

## T2 自动化与发布

- [x] 后端完整 `.\mvnw.cmd test`：764/764，0 失败/错误/跳过，日志 `target/history-export-final-tests.log`；导出新增 50 项（Controller 15、真实 SQLite/业务文件 26、renderer 8、业务布局 1）。renderer 专项 9/9，日志 `target/history-export-renderer-tests.log`。
- [x] 生成样例 PDF/XLSX：公共 renderer 与真实业务模型布局已渲染为 PNG 并逐页检查，中文/长理由续页/大 ID/完整尾 token/页脚正常；Excel 内容、类型、冻结/筛选和公式字符串已回读验证。目录 `target/history-export-preview`，布局样例为 `business-layout-sample.*`，真实 SQLite 样例为 `business-sample.*`；均为隔离测试数据，非厂内生产数据。
- [x] 前端专项 16/16、全量 Node 430/430、浏览器下载 3/3、Electron 原生下载 1/1、lint、正式/Mock Web/Desktop 构建及完整 `release:gate` 均通过。
- [x] 新服务端/客户端安装包隔离构建、源码和文件哈希记录；Windows 安装器契约、新 payload 临时 Local 启动/健康/托盘自测通过，包中导出类/查询/配置/中文字体及 POI/PDFBox 依赖已核对。客户端正式 ASAR、主进程与源码一致及 47 项 dist 文本无 Mock 扫描通过。
- [x] 新 payload 原生 launcher/自带 runtime 的真实 HTTP 导出冒烟：随机 loopback 端口、独立临时 SQLite、所有监控/发现/调度关闭；真实 bootstrap、ADMIN/OPERATOR JWT，两角色四份 PDF/XLSX 的内容、中文字体、大 ID、数据类型和下载头均通过（ADMIN 3 条，OPERATOR 本人 2 条）。匿名401、非终态400通过，自有 PID 已停止、临时数据库/初始化凭据已删除。证据 `target/history-export-packaged-smoke.json`，测试样例 `target/history-export-packaged-smoke-{admin,operator}.{pdf,xlsx}`，不含实际厂内数据。

## T3 外部验收

- [ ] 真实 Windows 覆盖安装：NOT RUN。
- [ ] 厂内真实数据导出、真实 MySQL 与原生 Excel/PDF 阅读器验收：NOT RUN。

本轮不修改数据库结构/生产数据，不执行安装或打印机物理动作；不能继承历史验证结论。

## T4 导出筛选与节纸表格改进（2026-10-08）

- [x] 核对用户反馈：原弹窗只展示上次筛选；正式 Excel 已有可见第二张明细（13列），默认首表为汇总，旧文件未缺记录。只读证据 `target/history-export-sheet-review.json`。
- [x] 弹窗独立可编辑状态/文件/打印机/创建人/结束时间筛选、恢复列表条件/重置全部、不修改列表，不显示旧条数；权限和取消/会话保护保持有效。日期显示完整秒级端点，手输 Enter/Tab 均按实际值验证；打印机建议有界读取并保留历史ID输入。
- [x] PDF 横向紧凑表格、重复表头、完整13字段、8.3pt 正文；真实业务模型30条普通记录为2页，极长文本明确引用全宽完整附录，任务编号完整保留。
- [x] 正式 Excel 明细第一且默认激活，保留汇总；非空记录为原生 `ExportDetails` Table，空结果保留筛选表头。350条跨流式窗口、13列真实业务记录、文本大ID/日期/数字/公式样式文本均回读通过；Mock 与弹窗由前端专项验证。
- [x] 后端全量与前端专项/全量/浏览器/发布门禁，PDF渲染视检、Excel文件回读。
- [x] 更新源码提交及两端安装包并独立保存本轮验证证据；真实覆盖安装/厂内数据仍为NOT RUN。

本轮后端专项39/39、完整 `.\mvnw.cmd test` 768/768，0失败/错误/跳过，日志为 `target/history-export-table-tests.log`、`target/history-export-table-final-tests.log`。公共renderer11项、真实业务布局2项、SQLite业务26项；本轮新增4项。首轮仅PDF文本提取测试helper错误地把8.3pt按整数获取，已按PDFBox正确API修正并重跑。真实30条普通记录PDF与两页极长原因附录已逐页视检；目录 `target/history-export-preview/table-normal-business-pngs`、`table-business-pngs`，样例使用隔离合成数据。接口、DTO、查询、权限和数据库结构没有变更。

本轮公共及真实业务布局共4份PDF、8张有效PNG已逐页视检，均为横向2页，无裁剪或叠字；证据 `target/history-export-table-layout-review.json`。首轮3页留下的旧 `page-3.png` 已明确忽略，不作为当前报告页数。前端专项19/19、Node433/433、浏览器4/4和Electron原生下载1/1通过；浏览器实际下载并回读明细首表、13字段和12条跨分页记录，独立筛选不改变列表，设备名称对应真实ID且精确秒级日期有效。完整 `release:gate` 通过，覆盖lint、Web/Desktop正式及Mock构建、Electron dist 1/1和源码/产物扫描；前端日志 `test-results/history-export-revision-*.log`。

新服务端包由源码 `3492e19` 构建，安装器契约、隔离Local启动/健康/托盘检查通过，包中表格/附录类、中文字体许可及依赖已核对，证据 `target/history-export-table-payload-verification.json`。真实新payload HTTP冒烟以临时SQLite、随机loopback和真实两角色JWT完成四份导出：ADMIN3条、OPERATOR本人2条；PDF各1页横向表格，Excel明细首active/selected、完整13列原生Table/筛选、大ID文本与数值时长均通过，匿名401/非法状态400通过。监控/发现/调度关闭，仅合成数据；自有PID44044已停止，临时数据库及bootstrap凭据已清理。证据 `target/history-export-table-packaged-smoke.json`，不替代真实安装或厂内数据验收。

## 表格改进交付产物（T4）

源码提交：后端 `3492e19a85d836fbac5bdea66b5fcf0052ffd5f5`，前端 `3658a54817763a7460122769ff95a2ce7b6c91cc`。产品版本保持 `1.0.0`。

核心修改：后端 `common/export/PdfReportWriter.java`、`ExcelReportWriter.java` 及3个renderer/真实SQLite测试类；前端 `src/views/JobHistory.vue`、`src/utils/fileExport.js`、`src/mock/historyExport.js`、Mock路由及专项/浏览器测试。规格、接口交接和版本索引同步更新；无新增SQL迁移。

| 产物 | 路径（各仓库相对路径） | 大小（字节） | SHA-256 |
| --- | --- | ---: | --- |
| Server | `dist/windows-installer-history-export-table-20261008/FabMatrix-Server-Setup-1.0.0.exe` | 145390082 | `264E06DB9159B208726C97A8CA2F77D72C72A27D9EDECBD5AFF2EF7C9B7FD732` |
| Client | `release/history-export-table-20261008-3658a54/FabMatrix-Client-Setup-1.0.0.exe` | 128290550 | `12AC26EB282C1A5B12353B51974FC25CD898BA91C5F3ECD0B22306A876A228EB` |

服务端构建使用 `scripts/windows/build-installer.ps1 -Mode Full -OutputDir dist/windows-installer-history-export-table-20261008 -ClientRoot D:\WorkSpace\Vue\fabmatrix-client -SkipTests -NoClean`，同源码全量测试已先通过；构建日志 `target/history-export-table-installer-build.log`。源码与哈希同时记录于产物目录的 `release-info.txt/checksums.txt`。新包与首版包隔离保存，未执行安装。

客户端构建使用 `npm.cmd run desktop:build -- --config.directories.output=release/history-export-table-20261008-3658a54`，日志 `test-results/history-export-table-installer-build.log/verification.log`。同目录含 `.exe.sha256/verification.json`；正式ASAR无Mock模式，主进程及48个打包dist文本文件与最终正式构建完全一致，包含独立筛选/重置/恢复/精确时间UI和导出接口，Mock导出fixture不在正式包中，扫描无发现。ASAR SHA-256为 `39E8ADDBADD6CF8E2F32BF5B1357CFAD8CA4B8EF0C1E4204D5EC7B0193E5E23A`。两端均未执行安装或真实厂内数据操作。

## 首版交付产物（T1/T2，保留历史证据）

源码提交：后端 `ed4f406`，前端 `526df89`。产品版本保持 `1.0.0`。

| 产物 | 路径（各仓库相对路径） | 大小（字节） | SHA-256 |
| --- | --- | ---: | --- |
| Server | `dist/windows-installer-history-export-20261008/FabMatrix-Server-Setup-1.0.0.exe` | 145382126 | `8DC2E16D90E45C4859593F4673731C3E0CE0ED8412050AA735E2D5A53D84D6EB` |
| Client | `release/history-export-20261008-526df89/FabMatrix-Client-Setup-1.0.0.exe` | 128287184 | `5F5764906D1998B12EF0E388321F51C5CDC828DBF511241C628523C1A8D943F6` |

服务端构建：`powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/windows/build-installer.ps1 -Mode Full -OutputDir dist/windows-installer-history-export-20261008 -ClientRoot D:\WorkSpace\Vue\fabmatrix-client -SkipTests -NoClean`，构建与新 payload 契约日志 `target/history-export-installer-build.log`；全量测试已在相同源码构建前通过。输出目录含 `release-info.txt/checksums.txt`。

客户端构建：`npm.cmd run desktop:build -- --config.directories.output=release/history-export-20261008-526df89`，同目录含 `.sha256/verification.json`；正式 ASAR 包版本 1.0.0、无 Mock 模式，包含导出接口和入口。旧包保留；两端均未执行安装。
