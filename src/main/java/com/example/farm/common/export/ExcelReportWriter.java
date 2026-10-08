package com.example.farm.common.export;

import com.example.farm.common.exception.BusinessException;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;

final class ExcelReportWriter {
    private static final int MAX_CELL_LENGTH = 32_767;

    void write(ExportReport report, OutputStream output) throws IOException {
        // A bounded row window keeps the workbook resident memory independent of record count.
        // close() disposes SXSSF temporary files, including when output exceeds its budget.
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            Styles styles = new Styles(workbook);
            writeSummary(workbook, report, styles);
            writeDetails(workbook, report, styles);
            workbook.write(output);
        }
    }

    private void writeSummary(SXSSFWorkbook workbook, ExportReport report, Styles styles) {
        SXSSFSheet sheet = workbook.createSheet("汇总");
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 84 * 256);
        Row title = sheet.createRow(0);
        title.setHeightInPoints(32);
        text(title.createCell(0), report.title(), styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
        Row generated = sheet.createRow(1);
        generated.setHeightInPoints(26);
        text(generated.createCell(0), "导出时间", styles.label);
        value(generated.createCell(1), report.generatedAt(), styles.body, styles.date);
        Row header = sheet.createRow(3);
        header.setHeightInPoints(26);
        text(header.createCell(0), "统计项目", styles.header);
        text(header.createCell(1), "值", styles.header);
        int index = 4;
        for (ExportReport.SummaryEntry entry : report.summary()) {
            Row row = sheet.createRow(index++);
            row.setHeightInPoints(Math.max(26, estimatedLines(ReportExportService.display(entry.value()), 84) * 15 + 8));
            text(row.createCell(0), entry.label(), styles.label);
            value(row.createCell(1), entry.value(), styles.body, styles.date);
        }
        sheet.createFreezePane(0, 4);
        configurePrint(sheet, false);
    }

    private void writeDetails(SXSSFWorkbook workbook, ExportReport report, Styles styles) {
        SXSSFSheet sheet = workbook.createSheet("明细");
        Row header = sheet.createRow(0);
        header.setHeightInPoints(28);
        for (int column = 0; column < report.columns().size(); column++) {
            ExportReport.Column definition = report.columns().get(column);
            sheet.setColumnWidth(column, definition.width() * 256);
            text(header.createCell(column), definition.label(), styles.header);
        }
        int rowIndex = 1;
        for (var values : report.rows()) {
            Row row = sheet.createRow(rowIndex++);
            int maxLines = 1;
            for (int column = 0; column < values.size(); column++) {
                Object cellValue = values.get(column);
                value(row.createCell(column), cellValue, styles.body, styles.date);
                maxLines = Math.max(maxLines, estimatedLines(ReportExportService.display(cellValue), report.columns().get(column).width()));
            }
            // Excel's maximum row height is 409.5pt; longer cells remain fully stored and editable.
            row.setHeightInPoints(Math.min(409, Math.max(26, maxLines * 15 + 8)));
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, report.columns().size() - 1));
        sheet.setRepeatingRows(new CellRangeAddress(0, 0, -1, -1));
        configurePrint(sheet, true);
    }

    private void configurePrint(SXSSFSheet sheet, boolean landscape) {
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setLandscape(landscape);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.getFooter().setRight("第 &P 页 / 共 &N 页");
    }

    private void value(Cell cell, Object value, CellStyle bodyStyle, CellStyle dateStyle) {
        if (value == null) {
            cell.setBlank();
            cell.setCellStyle(bodyStyle);
        } else if (value instanceof LocalDateTime dateTime) {
            cell.setCellValue(dateTime);
            cell.setCellStyle(dateStyle);
        } else if (value instanceof Number number && safelyNumeric(number)) {
            cell.setCellValue(number.doubleValue());
            cell.setCellStyle(bodyStyle);
        } else {
            // setCellValue(String) never parses formulas; IDs and long integers retain every digit.
            text(cell, value.toString(), bodyStyle);
        }
    }

    private boolean safelyNumeric(Number value) {
        if (!Double.isFinite(value.doubleValue())) {
            return false;
        }
        try {
            BigDecimal decimal = value instanceof BigInteger integer
                    ? new BigDecimal(integer) : new BigDecimal(value.toString());
            // Excel keeps at most 15 decimal significant digits even for integer-valued doubles.
            return decimal.stripTrailingZeros().precision() <= 15
                    && decimal.compareTo(BigDecimal.valueOf(value.doubleValue())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void text(Cell cell, String text, CellStyle style) {
        if (text.length() > MAX_CELL_LENGTH) {
            throw new BusinessException(422, "导出内容超过 Excel 单元格容量，请缩小查询范围或使用 PDF");
        }
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private int estimatedLines(String text, int width) {
        int lines = 1;
        int units = 0;
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            if (point == '\n') {
                lines++;
                units = 0;
            } else {
                units += point > 255 ? 2 : 1;
                if (units > width - 2) {
                    lines++;
                    units = point > 255 ? 2 : 1;
                }
            }
        }
        return lines;
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle label;
        final CellStyle body;
        final CellStyle date;

        Styles(SXSSFWorkbook workbook) {
            Font regular = workbook.createFont();
            regular.setFontName("等线");
            regular.setFontHeightInPoints((short) 11);
            body = workbook.createCellStyle();
            body.setFont(regular);
            body.setWrapText(true);
            body.setVerticalAlignment(VerticalAlignment.TOP);
            body.setBorderBottom(BorderStyle.HAIR);
            body.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            label = workbook.createCellStyle();
            label.cloneStyleFrom(body);
            label.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            label.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            date = workbook.createCellStyle();
            date.cloneStyleFrom(body);
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            Font headerFont = workbook.createFont();
            headerFont.setFontName("等线");
            headerFont.setFontHeightInPoints((short) 11);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.cloneStyleFrom(body);
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            title = workbook.createCellStyle();
            title.cloneStyleFrom(body);
            Font titleFont = workbook.createFont();
            titleFont.setFontName("等线");
            titleFont.setFontHeightInPoints((short) 18);
            titleFont.setBold(true);
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.LEFT);
        }
    }
}
