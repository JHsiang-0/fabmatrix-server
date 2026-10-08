package com.example.farm.common.export;

import com.example.farm.config.ExportProperties;
import com.example.farm.entity.dto.request.PrintJobHistoryExportRequest;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.service.PrintJobHistoryExportService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Visual fixture uses the real business report builder and real renderer with isolated data. */
class PrintJobBusinessReportPreviewTest {
    @Test
    void renderBusinessSummaryAndThirteenColumnsForVisualInspection() throws Exception {
        PrintJobMapper mapper = mock(PrintJobMapper.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        PrintJobSummaryVO completed = history(9007199254740993L, "COMPLETED", "生产零件-外壳.gcode", "车间 A 一号机");
        PrintJobSummaryVO failed = history(9007199254740994L, "FAILED", "齿轮机构.gcode", "车间 A 二号机");
        failed.setErrorReason("喷嘴温度检查失败；现场操作员已停止启动，等待检查热床和耗材后重新确认。".repeat(100) + "END_OF_BUSINESS_REASON");
        failed.setAttemptNumber(2);
        PrintJobSummaryVO cancelled = history(9007199254740995L, "CANCELED", "=SUM(批次).gcode 😀", "车间 B 三号机");
        cancelled.setStartedAt(null);
        cancelled.setOperatorUsernameSnapshot(null);
        cancelled.setErrorReason("现场取消，尚未启动");
        when(mapper.selectHistoryForExport(any(), isNull(), isNull(), eq(10L), anyBoolean(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(completed, failed, cancelled));
        var service = new PrintJobHistoryExportService(mapper, new ReportExportService(), new ExportProperties(), transactions);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(10L,
                "管理员", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            PrintJobHistoryExportRequest request = new PrintJobHistoryExportRequest();
            request.setFormat(ExportFormat.PDF);
            ExportDocument document = service.export(request);
            Path directory = Path.of("target", "history-export-preview");
            Files.createDirectories(directory.resolve("business-pngs"));
            Files.write(directory.resolve("business-layout-sample.pdf"), document.content());
            try (var pdf = Loader.loadPDF(document.content())) {
                var renderer = new PDFRenderer(pdf);
                for (int index = 0; index < pdf.getNumberOfPages(); index++) {
                    ImageIO.write(renderer.renderImageWithDPI(index, 110, ImageType.RGB), "png",
                            directory.resolve("business-pngs").resolve("page-" + (index + 1) + ".png").toFile());
                }
                String text = new PDFTextStripper().getText(pdf).replaceAll("\\s", "");
                assertThat(text).contains("时区", "Asia/Shanghai", "历史记录条数", "经过时长合计（分钟）", "统计口径", "时长口径",
                        "9007199254740993", "9007199254740994", "9007199254740995", "END_OF_BUSINESS_REASON", "（续）");
            }
            request.setFormat(ExportFormat.XLSX);
            Files.write(directory.resolve("business-layout-sample.xlsx"), service.export(request).content());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static PrintJobSummaryVO history(long id, String status, String filename, String printer) {
        PrintJobSummaryVO job = new PrintJobSummaryVO();
        job.setId(id);
        job.setStatus(status);
        job.setFileNameSnapshot(filename);
        job.setPrinterNameSnapshot(printer);
        job.setMachineNumberSnapshot("P-00" + (id % 10));
        job.setCreatorUsernameSnapshot("管理员 张");
        job.setOperatorUsernameSnapshot("现场操作员 李");
        job.setCreatedAt(LocalDateTime.of(2026, 10, 8, 8, 30));
        job.setStartedAt(LocalDateTime.of(2026, 10, 8, 9, 0));
        job.setCompletedAt(LocalDateTime.of(2026, 10, 8, 10, 30));
        job.setAttemptNumber(1);
        return job;
    }
}
