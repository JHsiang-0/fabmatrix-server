package com.example.farm.common.export;

import com.example.farm.common.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportExportServiceTest {
    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 10, 8, 16, 30, 12);
    private final ReportExportService service = new ReportExportService();

    @Test
    void excelRetainsSafeTypesSummaryDatesIdentifiersAndFormulaLookingText() throws Exception {
        List<Object> values = Arrays.asList("9223372036854775807", 9223372036854775807L,
                "=HYPERLINK(\"https://example.invalid\",\"click\")", "+cmd", "@SUM(A1:A2)",
                12, new BigDecimal("1.25"), GENERATED_AT, null, "中文\n换行", "打印完成 😀");
        List<ExportReport.Column> columns = java.util.stream.IntStream.range(0, values.size())
                .mapToObj(index -> new ExportReport.Column("字段 " + index, 24)).toList();
        ExportReport report = new ExportReport("打印历史", GENERATED_AT,
                List.of(new ExportReport.SummaryEntry("记录总数", 1), new ExportReport.SummaryEntry("查询范围", "全部历史")),
                columns, List.of(values));

        ExportDocument document = service.render(report, ExportFormat.XLSX, "打印历史测试");

        assertThat(document.filename()).isEqualTo("打印历史测试.xlsx");
        assertThat(document.contentType()).isEqualTo(ExportFormat.XLSX.contentType());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(document.content()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("明细");
            assertThat(workbook.getSheetName(1)).isEqualTo("汇总");
            assertThat(workbook.getActiveSheetIndex()).isZero();
            assertThat(workbook.getSheetAt(0).isSelected()).isTrue();
            assertThat(workbook.getSheetAt(1).isSelected()).isFalse();
            var summary = workbook.getSheet("汇总");
            assertThat(summary.getRow(0).getCell(0).getStringCellValue()).isEqualTo("打印历史");
            assertThat(summary.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(1);
            assertThat(summary.getRow(5).getCell(1).getStringCellValue()).isEqualTo("全部历史");
            var details = workbook.getSheet("明细");
            var row = details.getRow(1);
            for (int index : List.of(0, 1, 2, 3, 4, 9, 10)) {
                assertThat(row.getCell(index).getCellType()).isEqualTo(CellType.STRING);
                assertThat(row.getCell(index).getStringCellValue()).isEqualTo(values.get(index).toString());
            }
            assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(12);
            assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(1.25);
            assertThat(row.getCell(7).getLocalDateTimeCellValue()).isEqualTo(GENERATED_AT);
            assertThat(row.getCell(7).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm:ss");
            assertThat(row.getCell(8).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(9).getCellStyle().getWrapText()).isTrue();
            assertThat(details.getPaneInformation().isFreezePane()).isTrue();
            assertThat(details.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
            assertThat(details.getCTWorksheet().isSetAutoFilter()).isFalse();
            assertThat(details.getTables()).hasSize(1);
            var table = details.getTables().getFirst();
            assertThat(table.getName()).isEqualTo("ExportDetails");
            assertThat(table.getCTTable().getAutoFilter().getRef()).isEqualTo("A1:K2");
            assertThat(table.getCTTable().getTableStyleInfo().getShowRowStripes()).isTrue();
            assertThat(table.getCTTable().getTableColumns().sizeOfTableColumnArray()).isEqualTo(values.size());
            for (int index = 0; index < values.size(); index++) {
                assertThat(table.getCTTable().getTableColumns().getTableColumnArray(index).getName()).isEqualTo("字段 " + index);
            }
        }
    }

    @Test
    void excelWritesAllRowsBeyondTheStreamingWindow() throws Exception {
        List<List<Object>> rows = java.util.stream.IntStream.range(0, 350)
                .mapToObj(index -> List.<Object>of("任务-" + index, index)).toList();
        var report = new ExportReport("流式测试", GENERATED_AT, List.of(),
                List.of(new ExportReport.Column("任务", 20), new ExportReport.Column("数量", 12)), rows);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.render(report, ExportFormat.XLSX, "streaming").content()))) {
            var sheet = workbook.getSheet("明细");
            assertThat(sheet.getLastRowNum()).isEqualTo(350);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("任务-0");
            assertThat(sheet.getRow(350).getCell(0).getStringCellValue()).isEqualTo("任务-349");
            assertThat(sheet.getTables().getFirst().getCTTable().getRef()).isEqualTo("A1:B351");
            assertThat(sheet.getTables().getFirst().getCTTable().getTableColumns().getTableColumnArray(0).getName()).isEqualTo("任务");
        }
    }

    @Test
    void pdfEmbedsChineseFontPaginatesLongTextAndPreservesEveryRecord() throws Exception {
        String longReason = "喷嘴检查未通过，需要人工检查热床和耗材。".repeat(180) + "END_OF_LONG_REASON";
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("9223372036854775807", "模型中文.gcode", "FAILED", longReason));
        rows.add(Arrays.asList("9223372036854775806", "emoji 😀", "COMPLETED", null));
        for (int index = 0; index < 12; index++) {
            rows.add(List.of("任务唯一标识" + index, "EnglishUnbrokenToken".repeat(15), "COMPLETED", "完成"));
        }
        var report = new ExportReport("打印历史导出", GENERATED_AT,
                List.of(new ExportReport.SummaryEntry("记录总数", rows.size()), new ExportReport.SummaryEntry("查询范围", "2026-10-01 至 2026-10-08")),
                List.of(new ExportReport.Column("任务编号", 24), new ExportReport.Column("文件名", 32),
                        new ExportReport.Column("状态", 16), new ExportReport.Column("错误原因", 60)), rows);

        ExportDocument exported = service.render(report, ExportFormat.PDF, "history");

        assertThat(exported.contentType()).isEqualTo("application/pdf");
        assertThat(exported.filename()).isEqualTo("history.pdf");
        try (PDDocument pdf = Loader.loadPDF(exported.content())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text.replaceAll("\\s", "")).contains("打印历史导出", "记录总数", "9223372036854775807", "9223372036854775806",
                    "END_OF_LONG_REASON", "[U+1F600]", "（续）", "FabMatrix", "见附录A1", "长文本附录");
            for (int index = 0; index < 12; index++) {
                assertThat(text).contains("任务唯一标识" + index);
            }
            assertThat(text.replaceAll("\\s", "")).contains("EnglishUnbrokenToken".repeat(15));
            assertThat(pdf.getDocumentInformation().getTitle()).isEqualTo(report.title());
            for (var page : pdf.getPages()) {
                assertThat(page.getMediaBox().getWidth()).isCloseTo(841.89f, org.assertj.core.data.Offset.offset(0.1f));
                assertThat(page.getMediaBox().getHeight()).isCloseTo(595.28f, org.assertj.core.data.Offset.offset(0.1f));
                for (var fontName : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(fontName);
                    assertThat(font.isEmbedded()).isTrue();
                    assertThat(font.getName()).contains("NotoSansSC");
                }
            }
            new BoundsCheckingStripper().getText(pdf);
        }
    }

    @Test
    void emptyReportsRemainValidInBothFormats() throws Exception {
        var report = new ExportReport("无记录", GENERATED_AT, List.of(), List.of(new ExportReport.Column("任务", 20)), List.of());
        try (var pdf = Loader.loadPDF(service.render(report, ExportFormat.PDF, "empty").content())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(pdf)).contains("没有符合条件的历史记录", "0 条记录");
        }
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.render(report, ExportFormat.XLSX, "empty").content()))) {
            assertThat(workbook.getSheet("明细").getLastRowNum()).isZero();
            assertThat(workbook.getActiveSheetIndex()).isZero();
            assertThat(workbook.getSheet("明细").getTables()).isEmpty();
            assertThat(workbook.getSheet("明细").getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1");
        }
    }

    @Test
    void thirtyNormalThirteenColumnRecordsUseAtMostThreeReadableTablePages() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            rows.add(Arrays.asList("900719925474" + String.format(java.util.Locale.ROOT, "%06d", index),
                    "零件外壳-" + index + ".gcode", "车间 A 一号机", "P-001", "管理员 张", "操作员 李", "已完成",
                    GENERATED_AT.minusHours(4), GENERATED_AT.minusHours(3), GENERATED_AT,
                    new BigDecimal("180.00"), 1, null));
        }
        var report = new ExportReport("打印历史", GENERATED_AT,
                List.of(new ExportReport.SummaryEntry("结束时间范围", "2026-10-01 00:00:00 至 2026-10-08 16:30:12"),
                        new ExportReport.SummaryEntry("筛选条件", "状态=已完成、失败、已取消；文件=全部；打印机ID=全部；创建人ID=全部"),
                        new ExportReport.SummaryEntry("时区", "Asia/Shanghai"), new ExportReport.SummaryEntry("历史记录条数", 30),
                        new ExportReport.SummaryEntry("已完成", 30), new ExportReport.SummaryEntry("失败", 0),
                        new ExportReport.SummaryEntry("已取消", 0), new ExportReport.SummaryEntry("经过时长合计（分钟）", 5400),
                        new ExportReport.SummaryEntry("统计口径", "每次任务尝试为一条历史记录，条数不代表打印件数；仅平台任务，不含设备外部打印"),
                        new ExportReport.SummaryEntry("时长口径", "开始至结束的经过时长，包含暂停；缺失或异常时间留空")),
                businessColumns(), rows);
        byte[] bytes = service.render(report, ExportFormat.PDF, "dense-table").content();
        Path directory = Path.of("target", "history-export-preview", "table-pngs");
        Files.createDirectories(directory);
        Files.write(directory.getParent().resolve("dense-table.pdf"), bytes);
        Files.write(directory.getParent().resolve("dense-table.xlsx"), service.render(report, ExportFormat.XLSX, "dense-table").content());
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int index = 0; index < pdf.getNumberOfPages(); index++) {
                ImageIO.write(renderer.renderImageWithDPI(index, 130, ImageType.RGB), "png",
                        directory.resolve("page-" + (index + 1) + ".png").toFile());
            }
            assertThat(pdf.getNumberOfPages()).isBetween(1, 3);
            String text = new PDFTextStripper().getText(pdf);
            for (List<Object> row : rows) {
                assertThat(text).contains(row.getFirst().toString());
            }
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                assertThat(stripper.getText(pdf)).contains("任务编号");
            }
            new BoundsCheckingStripper().getText(pdf);
        }
    }

    @Test
    void unusuallyLongNumericCellContinuesWithinRepeatedHeaderWithoutLosingDigits() throws Exception {
        String identifier = "1234567890".repeat(1_500);
        var report = new ExportReport("长标识表格", GENERATED_AT, List.of(),
                List.of(new ExportReport.Column("完整标识", 100)), List.of(List.of(identifier)));
        try (var pdf = Loader.loadPDF(service.render(report, ExportFormat.PDF, "long-row").content())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("记录 1（续）");
            String digits = text.replaceAll("[^0-9]", "");
            // Page numbers/continuation labels occur between chunks. Count the exact
            // repeated identifier subsequence in text positions within the table body.
            var extractor = new NumericCellStripper();
            extractor.getText(pdf);
            assertThat(extractor.content.toString()).isEqualTo(identifier);
            assertThat(digits.length()).isGreaterThanOrEqualTo(identifier.length());
            new BoundsCheckingStripper().getText(pdf);
        }
    }

    @Test
    void structuredTableHeadersAreUniqueAndKeepFormulaLookingLabelsAsText() throws Exception {
        var report = new ExportReport("表头", GENERATED_AT, List.of(),
                List.of(new ExportReport.Column("名称", 20), new ExportReport.Column("名称", 20),
                        new ExportReport.Column("=SUM(A1)", 20)), List.of(List.of("a", "b", "c")));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.render(report, ExportFormat.XLSX, "headers").content()))) {
            var sheet = workbook.getSheet("明细");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("名称");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("名称 (2)");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("=SUM(A1)");
            assertThat(sheet.getRow(0).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getTables().getFirst().getCTTable().getTableColumns().getTableColumnArray(1).getName()).isEqualTo("名称 (2)");
        }
    }

    private List<ExportReport.Column> businessColumns() {
        return List.of(new ExportReport.Column("任务编号", 22), new ExportReport.Column("文件名", 30),
                new ExportReport.Column("打印机", 20), new ExportReport.Column("机台号", 16),
                new ExportReport.Column("创建人", 18), new ExportReport.Column("操作员", 18),
                new ExportReport.Column("结果", 12), new ExportReport.Column("创建时间", 22),
                new ExportReport.Column("开始时间", 22), new ExportReport.Column("结束时间", 22),
                new ExportReport.Column("经过时长（分钟）", 20), new ExportReport.Column("尝试序号", 12),
                new ExportReport.Column("原因", 40));
    }

    @Test
    void previewsExerciseTheSameProductionWriterAndRenderEveryPdfPage() throws Exception {
        Path directory = Path.of("target", "history-export-preview");
        Files.createDirectories(directory.resolve("table-long-text-pngs"));
        ExportReport report = previewReport();
        byte[] pdfBytes = service.render(report, ExportFormat.PDF, "sample").content();
        Files.write(directory.resolve("sample.pdf"), pdfBytes);
        Files.write(directory.resolve("sample.xlsx"), service.render(report, ExportFormat.XLSX, "sample").content());
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                ImageIO.write(renderer.renderImageWithDPI(index, 110, ImageType.RGB), "png",
                        directory.resolve("table-long-text-pngs").resolve("page-" + (index + 1) + ".png").toFile());
            }
            new BoundsCheckingStripper().getText(document);
        }
        assertThat(directory.resolve("sample.xlsx")).exists();
        assertThat(directory.resolve("table-long-text-pngs/page-1.png")).exists();
    }

    @Test
    void reportAndDocumentSnapshotsDoNotExposeMutableListsOrBytes() {
        var row = new ArrayList<Object>(Arrays.asList("first", null));
        var rows = new ArrayList<List<Object>>(List.of(row));
        var report = new ExportReport("snapshot", GENERATED_AT, List.of(),
                List.of(new ExportReport.Column("A", 12), new ExportReport.Column("B", 12)), rows);
        row.set(0, "modified");
        rows.clear();
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().getFirst()).containsExactly("first", null);
        assertThatThrownBy(() -> report.rows().getFirst().set(0, "x")).isInstanceOf(UnsupportedOperationException.class);
        byte[] bytes = {1, 2};
        var document = new ExportDocument("x.pdf", "application/pdf", bytes);
        bytes[0] = 3;
        document.content()[0] = 4;
        assertThat(document.content()).containsExactly((byte) 1, (byte) 2);
    }

    @Test
    void oversizedCellsAndInvalidFilenamesFailExplicitlyWithoutSilentTruncation() {
        var report = new ExportReport("容量测试", GENERATED_AT, List.of(),
                List.of(new ExportReport.Column("说明", 24)), List.of(List.of("文".repeat(32_768))));
        assertThatThrownBy(() -> service.render(report, ExportFormat.XLSX, "oversize"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo(422));
        assertThatThrownBy(() -> service.render(report, ExportFormat.PDF, "../report"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.render(report, ExportFormat.PDF, "report\r\nInjected"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responseBudgetChecksBeforeAcceptingBytesAndWriterPropagatesExceededBudget() throws Exception {
        var output = new ReportExportService.LimitedOutputStream(3);
        output.write(new byte[]{1, 2});
        output.write(3);
        assertThatThrownBy(() -> output.write(4)).isInstanceOf(ReportExportService.DocumentTooLargeException.class);
        assertThatThrownBy(() -> output.write(new byte[]{5, 6}, 0, 2)).isInstanceOf(ReportExportService.DocumentTooLargeException.class);
        assertThat(output.toByteArray()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        var report = new ExportReport("budget", GENERATED_AT, List.of(), List.of(new ExportReport.Column("任务", 20)), List.of());
        Set<Path> before = streamingTemporaryFiles();
        for (ExportFormat format : ExportFormat.values()) {
            assertThatThrownBy(() -> service.render(report, format, "limited", 1))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(422));
        }
        assertThat(streamingTemporaryFiles()).isEqualTo(before);
    }

    private Set<Path> streamingTemporaryFiles() throws IOException {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "poifiles");
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("poi-sxssf"))
                    .collect(Collectors.toSet());
        }
    }

    private ExportReport previewReport() {
        String longReason = "设备返回温度检查失败；现场操作员已停止启动并检查喷嘴、热床和耗材，等待重新确认。".repeat(70)
                + "END_OF_PREVIEW_REASON";
        var columns = List.of(new ExportReport.Column("任务编号", 25), new ExportReport.Column("文件名", 35),
                new ExportReport.Column("打印机", 24), new ExportReport.Column("机器编号", 18),
                new ExportReport.Column("创建人", 20), new ExportReport.Column("现场操作员", 20),
                new ExportReport.Column("结果", 14), new ExportReport.Column("创建时间", 22),
                new ExportReport.Column("开始时间", 22), new ExportReport.Column("结束时间", 22),
                new ExportReport.Column("经过时长", 18), new ExportReport.Column("错误原因", 60),
                new ExportReport.Column("重试次数", 14));
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList("9223372036854775807", "齿轮外壳-生产批次-20261008.gcode", "车间 A · 01 号机", "P-001",
                "管理员", "张师傅", "失败", GENERATED_AT.minusHours(6), GENERATED_AT.minusHours(5), GENERATED_AT.minusHours(4),
                "1 小时（包含暂停）", longReason, 1));
        rows.add(Arrays.asList("9223372036854775806", "=SUM(生产记录).gcode", "车间 A · 02 号机", "P-002",
                "操作员 李", "李师傅", "已完成", GENERATED_AT.minusHours(4), GENERATED_AT.minusHours(3), GENERATED_AT.minusHours(1),
                "2 小时（包含暂停）", null, 0));
        rows.add(Arrays.asList("9223372036854775805", "EnglishUnbrokenFilename".repeat(20) + ".gcode 😀", "车间 B · 03 号机", "P-003",
                "操作员 陈", null, "已取消", GENERATED_AT.minusHours(1), null, GENERATED_AT,
                null, "现场取消，尚未启动", 0));
        return new ExportReport("打印历史报表", GENERATED_AT,
                List.of(new ExportReport.SummaryEntry("查询时间", "2026-10-01 00:00:00 至 2026-10-08 16:30:12"),
                        new ExportReport.SummaryEntry("时区", "Asia/Shanghai"),
                        new ExportReport.SummaryEntry("记录总数", 3), new ExportReport.SummaryEntry("完成 / 失败 / 取消", "1 / 1 / 1"),
                        new ExportReport.SummaryEntry("经过时长", "3 小时，包含暂停时间"),
                        new ExportReport.SummaryEntry("导出范围", "当前筛选条件下的全部平台任务历史")), columns, rows);
    }

    private static final class BoundsCheckingStripper extends PDFTextStripper {
        private BoundsCheckingStripper() throws IOException {
        }

        @Override
        protected void processTextPosition(TextPosition position) {
            assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(23f);
            assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThanOrEqualTo(819f);
            assertThat(position.getYDirAdj()).isGreaterThanOrEqualTo(23f);
            assertThat(position.getYDirAdj()).isLessThanOrEqualTo(585f);
            if (position.getYDirAdj() < 550) {
                assertThat(position.getFontSizeInPt()).isGreaterThanOrEqualTo(8f);
            }
            super.processTextPosition(position);
        }
    }

    private static final class NumericCellStripper extends PDFTextStripper {
        final StringBuilder content = new StringBuilder();

        private NumericCellStripper() throws IOException {
        }

        @Override
        protected void processTextPosition(TextPosition position) {
            // PDFBox's getFontSizeInPt() truncates to an integer; getFontSize()
            // retains the actual 8.3pt Tf size and separates 8pt continuation labels.
            if (Math.abs(position.getFontSize() - 8.3f) < 0.1f && position.getYDirAdj() < 555
                    && position.getUnicode().matches("[0-9]")) {
                content.append(position.getUnicode());
            }
            super.processTextPosition(position);
        }
    }
}
