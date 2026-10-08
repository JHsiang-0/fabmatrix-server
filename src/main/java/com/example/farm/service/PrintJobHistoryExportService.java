package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.export.ExportDocument;
import com.example.farm.common.export.ExportFormat;
import com.example.farm.common.export.ExportReport;
import com.example.farm.common.export.ExportReport.Column;
import com.example.farm.common.export.ExportReport.SummaryEntry;
import com.example.farm.common.export.ReportExportService;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.config.ExportProperties;
import com.example.farm.entity.dto.request.PrintJobHistoryExportRequest;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.mapper.PrintJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 业务范围和数据口径属于任务服务；公共导出模块仅负责文件生成。 */
@Service
@RequiredArgsConstructor
public class PrintJobHistoryExportService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private static final ZoneId FARM_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<Column> COLUMNS = List.of(
            new Column("任务编号", 22), new Column("文件名", 30), new Column("打印机", 20),
            new Column("机台号", 16), new Column("创建人", 18), new Column("操作员", 18),
            new Column("结果", 12), new Column("创建时间", 22), new Column("开始时间", 22),
            new Column("结束时间", 22), new Column("经过时长（分钟）", 20),
            new Column("尝试序号", 12), new Column("原因", 40));

    private final PrintJobMapper mapper;
    private final ReportExportService reportExportService;
    private final ExportProperties properties;
    private final PlatformTransactionManager transactionManager;

    public ExportDocument export(PrintJobHistoryExportRequest request) {
        // 服务边界也校验角色，确保绕过 Controller 的调用不会扩大访问范围。
        String role = SecurityContextUtil.getCurrentRole();
        if (!"ADMIN".equals(role) && !"OPERATOR".equals(role)) {
            throw new BusinessException(403, "没有打印历史导出权限");
        }
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (currentUserId == null || currentUserId <= 0) {
            throw new BusinessException(403, "用户身份无效");
        }
        NormalizedQuery query = normalize(request, currentUserId, "ADMIN".equals(role));
        LocalDateTime generatedAt = LocalDateTime.now(FARM_ZONE);
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setTimeout(30);
        ExportReport report = read.execute(transaction -> {
            List<PrintJobSummaryVO> history = mapper.selectHistoryForExport(query.storageStatuses(),
                    request.getPrinterId(), request.getUserId(), currentUserId, query.admin(),
                    query.escapedFileName(), request.getStartTime(), request.getEndTime(), query.maxRows() + 1);
            if (history.size() > query.maxRows()) {
                throw new BusinessException(422, "导出记录超过" + query.maxRows() + "条，请缩小筛选范围");
            }
            return buildReport(history, request, query, generatedAt);
        });
        // 文档排版发生在读事务结束之后，避免慢速 PDF/Excel 生成占用数据库事务。
        return reportExportService.render(report, request.getFormat(), "打印历史-" + TIMESTAMP.format(generatedAt));
    }

    private NormalizedQuery normalize(PrintJobHistoryExportRequest request, Long currentUserId, boolean admin) {
        if (request == null || request.getFormat() == null) {
            throw new BusinessException(400, "导出格式不能为空");
        }
        if (request.getPrinterId() != null && request.getPrinterId() <= 0
                || request.getUserId() != null && request.getUserId() <= 0) {
            throw new BusinessException(400, "筛选ID必须大于0");
        }
        if (request.getStartTime() != null && request.getEndTime() != null
                && request.getStartTime().isAfter(request.getEndTime())) {
            throw new BusinessException(400, "开始时间不能晚于结束时间");
        }
        List<String> raw = request.getStatuses() == null
                ? List.of("COMPLETED", "FAILED", "CANCELLED") : request.getStatuses();
        if (raw.isEmpty() || raw.size() > 3) {
            throw new BusinessException(400, "历史状态至少选择1项，最多3项");
        }
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        for (String status : raw) {
            String normalized = PrintJobStatus.normalize(status);
            if (normalized == null || !TERMINAL_STATUSES.contains(normalized)) {
                throw new BusinessException(400, "只能导出已完成、失败或已取消的历史任务");
            }
            canonical.add(normalized);
        }
        List<String> storage = new ArrayList<>(canonical);
        if (canonical.contains("CANCELLED")) storage.add(PrintJobStatus.LEGACY_CANCELED);
        String fileName = request.getFileName() == null ? null : request.getFileName().trim();
        if (fileName != null && fileName.length() > 255) {
            throw new BusinessException(400, "文件名筛选最多255个字符");
        }
        if (fileName != null && fileName.isEmpty()) fileName = null;
        String escaped = fileName == null ? null
                : fileName.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        int limit = request.getFormat() == ExportFormat.PDF ? properties.getPdfMaxRows() : properties.getXlsxMaxRows();
        int hardLimit = request.getFormat() == ExportFormat.PDF ? 1000 : 10000;
        if (limit <= 0 || limit > hardLimit) {
            throw new BusinessException(503, "导出条数限制配置无效");
        }
        return new NormalizedQuery(List.copyOf(storage), List.copyOf(canonical), fileName, escaped,
                admin ? request.getUserId() : currentUserId, admin, limit);
    }

    private ExportReport buildReport(List<PrintJobSummaryVO> history, PrintJobHistoryExportRequest request,
                                     NormalizedQuery query, LocalDateTime generatedAt) {
        List<List<Object>> rows = new ArrayList<>(history.size());
        int completed = 0;
        int failed = 0;
        int cancelled = 0;
        BigDecimal totalMinutes = BigDecimal.ZERO.setScale(2);
        for (PrintJobSummaryVO job : history) {
            String status = PrintJobStatus.normalize(job.getStatus());
            switch (status) {
                case "COMPLETED" -> completed++;
                case "FAILED" -> failed++;
                case "CANCELLED" -> cancelled++;
                default -> throw new BusinessException(503, "历史查询返回了未结束的任务");
            }
            BigDecimal minutes = elapsedMinutes(job);
            if (minutes != null) totalMinutes = totalMinutes.add(minutes);
            // 任务编号固定为文本，保留超过 JavaScript/Excel 精确整数范围的完整编号。
            rows.add(Arrays.asList(job.getId() == null ? null : job.getId().toString(),
                    historicalName(job.getFileNameSnapshot(), job.getFileName()),
                    historicalName(job.getPrinterNameSnapshot(), job.getPrinterName()),
                    historicalName(job.getMachineNumberSnapshot(), job.getMachineNumber()),
                    historicalName(job.getCreatorUsernameSnapshot(), job.getCreatorUsername()),
                    historicalName(job.getOperatorUsernameSnapshot(), job.getOperatorUsername()),
                    statusLabel(status), job.getCreatedAt(), job.getStartedAt(), job.getCompletedAt(),
                    minutes, job.getAttemptNumber(), job.getErrorReason()));
        }
        List<SummaryEntry> summary = List.of(
                new SummaryEntry("结束时间范围", range(request.getStartTime(), request.getEndTime())),
                new SummaryEntry("筛选条件", queryDescription(request, query)),
                new SummaryEntry("时区", "Asia/Shanghai"),
                new SummaryEntry("历史记录条数", history.size()),
                new SummaryEntry("已完成", completed), new SummaryEntry("失败", failed),
                new SummaryEntry("已取消", cancelled),
                new SummaryEntry("经过时长合计（分钟）", totalMinutes),
                new SummaryEntry("统计口径", "每次任务尝试为一条历史记录，条数不代表打印件数；仅平台任务，不含设备外部打印"),
                new SummaryEntry("时长口径", "开始至结束的经过时长，包含暂停；缺失或异常时间留空"));
        return new ExportReport("打印历史", generatedAt, summary, COLUMNS, rows);
    }

    private static String historicalName(String snapshot, String current) {
        return snapshot != null && !snapshot.isBlank() ? snapshot : current;
    }

    private static BigDecimal elapsedMinutes(PrintJobSummaryVO job) {
        if (job.getStartedAt() == null || job.getCompletedAt() == null
                || job.getCompletedAt().isBefore(job.getStartedAt())) return null;
        return BigDecimal.valueOf(Duration.between(job.getStartedAt(), job.getCompletedAt()).getSeconds())
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "COMPLETED" -> "已完成";
            case "FAILED" -> "失败";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    private static String range(LocalDateTime from, LocalDateTime to) {
        return (from == null ? "不限起点" : from.toString().replace('T', ' ')) + " 至 "
                + (to == null ? "不限终点" : to.toString().replace('T', ' '));
    }

    private static String queryDescription(PrintJobHistoryExportRequest request, NormalizedQuery query) {
        return "状态=" + String.join("、", query.canonicalStatuses().stream().map(PrintJobHistoryExportService::statusLabel).toList())
                + "；文件=" + (query.fileName() == null ? "全部" : query.fileName())
                + "；打印机ID=" + (request.getPrinterId() == null ? "全部" : request.getPrinterId())
                + "；创建人ID=" + (query.effectiveUserId() == null ? "全部" : query.effectiveUserId());
    }

    private record NormalizedQuery(List<String> storageStatuses, List<String> canonicalStatuses,
                                    String fileName, String escapedFileName, Long effectiveUserId,
                                    boolean admin, int maxRows) { }
}
