# 打印历史导出设计

## API 与模块

`PrintJobExportController` 接收 `PrintJobHistoryExportRequest`，调用 `PrintJobHistoryExportService`，成功返回二进制附件；错误沿用 `Result`。Spring Security 和服务层均验证 ADMIN/OPERATOR；服务层将 JWT 用户身份传入 Mapper，操作员提交其他创建人也不能扩大查询范围。

请求字段为 `format(XLSX|PDF)`、可选 `statuses/fileName/printerId/userId/startTime/endTime`；默认三个终态。没有分页和 `timeField` 参数。文件名 LIKE 规则复用现有历史列表，转义 `%/_/反斜线`；时间固定针对 `completed_at`，范围包含端点，无日期筛选时保留缺失结束时间的旧终态记录并排到最后。

Mapper 复用摘要 JOIN、历史快照和 MySQL/SQLite 方言。单次 `limit + 1` 查询在短只读事务内完成；超限拒绝，不截断。把记录转成 `ExportReport` 后结束事务，再交给 `ReportExportService` 渲染。汇总与明细来自同一批记录。

SQLite 旧记录和 JDBC 写入可能混用空格/ISO `T`、省略零秒和不同小数位。列表与导出共用受控文本日期键，仅在查询时补齐秒与九位小数，保留纳秒排序/范围精度；不写回已有数据。MySQL 保持原生时间字段比较。

公共模块位于 `common/export`：`ExportFormat`、`ExportReport`、`ExportDocument`、`ReportExportService` 及 Excel/PDF renderer。不依赖打印业务 Entity、Mapper 或权限。Excel 使用 Apache POI，PDF 使用 Apache PDFBox 和随包附带的 OFL 中文字体；生成内容限制为 20 MiB，失败时释放 workbook、临时文件和 PDF 资源。

## 内容口径

明细包含任务编号、文件名、打印机、机台号、创建人、操作员、结果、创建/开始/结束时间、经过分钟、尝试序号、原因。名称快照优先；无实际信息留空。任务 ID 为文本，日期为 Excel 日期，时长为数字。经过分钟由开始和结束计算，含暂停；汇总明确平台任务范围和记录条数含义。

## HTTP 与前端

附件提供正确 Content-Type、UTF-8 Content-Disposition、Content-Length 和 `Cache-Control: no-store`。CORS 显式暴露 Content-Disposition 与请求 trace ID。参数错误 400、无认证 401、权限错误 403、行数或字节超限 422、生成失败 500；不把业务错误保存成文件。

前端通过已应用筛选快照构造查询和导出请求，改动表单需查询后才用于导出。提供 Excel/PDF 入口和加载状态；全局 Axios 的可选二进制模式保留响应头并解码 Blob JSON 错误。下载使用安全文件名和短生命周期 object URL；组件卸载、会话变化或请求取消后忽略旧响应。

## 配置与未来扩展

`farm.export.xlsx-max-rows` 默认 10000、`pdf-max-rows` 默认 1000，可通过 `FARM_EXPORT_XLSX_MAX_ROWS/FARM_EXPORT_PDF_MAX_ROWS` 降低；硬上限保护同步内存/生成开销。未来操作历史保留 `/audit-logs/export` 等业务接口，直接复用报告模型和 renderer；大数据异步导出需另立规格。

无新增表、迁移、RustFS 依赖或设备副作用。保持现有产品版本和手动打印主流程。
