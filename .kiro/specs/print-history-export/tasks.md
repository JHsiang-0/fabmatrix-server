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
- [ ] 新服务端/客户端安装包隔离构建、源码和文件哈希记录。

## T3 外部验收

- [ ] 真实 Windows 覆盖安装：NOT RUN。
- [ ] 厂内真实数据导出、真实 MySQL 与原生 Excel/PDF 阅读器验收：NOT RUN。

本轮不修改数据库结构/生产数据，不执行安装或打印机物理动作；不能继承历史验证结论。
