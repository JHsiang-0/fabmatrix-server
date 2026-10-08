package com.example.farm.common.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Paginated record blocks keep wide reports legible without shrinking fourteen columns. */
final class PdfReportWriter {
    private static final String FONT_RESOURCE = "/export/fonts/NotoSansSC-Regular.ttf";
    private static final float MARGIN = 42;
    private static final float BOTTOM = 48;
    private static final float LABEL_WIDTH = 124;
    private static final float TEXT_SIZE = 9.5f;
    private static final float LEADING = 14;
    private static final Color INK = new Color(30, 45, 60);
    private static final Color MUTED = new Color(91, 108, 121);
    private static final Color ACCENT = new Color(18, 91, 105);
    private static final Color PALE = new Color(235, 244, 246);

    void write(ExportReport report, OutputStream output) throws IOException {
        try (PDDocument document = new PDDocument();
             InputStream fontResource = PdfReportWriter.class.getResourceAsStream(FONT_RESOURCE)) {
            if (fontResource == null) {
                throw new IOException("导出中文字体资源缺失");
            }
            PDType0Font font = PDType0Font.load(document, fontResource, true);
            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle(report.title());
            information.setCreator("FabMatrix Server");
            information.setSubject("生成时间 " + ReportExportService.display(report.generatedAt()));
            document.setDocumentInformation(information);
            try (Layout layout = new Layout(document, font, report)) {
                layout.newPage(true);
                layout.section("查询与统计");
                for (ExportReport.SummaryEntry summary : report.summary()) {
                    layout.field(summary.label(), ReportExportService.display(summary.value()), 0);
                }
                layout.gap(8);
                layout.section("历史明细 · " + report.rows().size() + " 条记录");
                if (report.rows().isEmpty()) {
                    layout.field("查询结果", "没有符合条件的历史记录", 0);
                }
                for (int index = 0; index < report.rows().size(); index++) {
                    layout.record(index + 1, report.rows().get(index));
                }
                layout.gap(8);
                layout.note("空值以 - 表示。字体不支持的字符使用 [U+编码] 保留。时间按报告注明的时区显示。");
            }
            writeFooters(document, font);
            document.save(output);
        }
    }

    private void writeFooters(PDDocument document, PDType0Font font) throws IOException {
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            PDPage page = document.getPage(index);
            String numbering = "第 " + (index + 1) + " 页 / 共 " + document.getNumberOfPages() + " 页";
            float width = font.getStringWidth(numbering) / 1000 * 8;
            try (PDPageContentStream stream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                stream.setStrokingColor(new Color(214, 224, 230));
                stream.moveTo(MARGIN, 37);
                stream.lineTo(page.getMediaBox().getWidth() - MARGIN, 37);
                stream.stroke();
                text(stream, font, "FabMatrix", MARGIN, 23, 8, MUTED);
                text(stream, font, numbering, page.getMediaBox().getWidth() - MARGIN - width, 23, 8, MUTED);
            }
        }
    }

    private static void text(PDPageContentStream stream, PDType0Font font, String value,
                             float x, float y, float size, Color color) throws IOException {
        if (value.isEmpty()) {
            return;
        }
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    private static final class Layout implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private final ExportReport report;
        private final float width = PDRectangle.A4.getWidth() - MARGIN * 2;
        private final Map<Integer, String> glyphs = new HashMap<>();
        private PDPageContentStream stream;
        private float cursor;

        private Layout(PDDocument document, PDType0Font font, ExportReport report) {
            this.document = document;
            this.font = font;
            this.report = report;
        }

        void newPage(boolean first) throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            cursor = PDRectangle.A4.getHeight() - MARGIN;
            float titleSize = first ? 20 : 11;
            for (String line : wrap(report.title(), titleSize, width)) {
                text(stream, font, line, MARGIN, cursor - titleSize, titleSize, INK);
                cursor -= titleSize + 7;
            }
            if (first) {
                text(stream, font, "导出时间：" + ReportExportService.display(report.generatedAt()),
                        MARGIN, cursor - 9, 9, MUTED);
                cursor -= 25;
            } else {
                cursor -= 5;
            }
            stream.setStrokingColor(ACCENT);
            stream.setLineWidth(1.3f);
            stream.moveTo(MARGIN, cursor);
            stream.lineTo(MARGIN + width, cursor);
            stream.stroke();
            cursor -= 20;
        }

        void section(String title) throws IOException {
            if (cursor - 40 < BOTTOM) {
                newPage(false);
            }
            for (String line : wrap(title, 11, width)) {
                text(stream, font, line, MARGIN, cursor - 11, 11, ACCENT);
                cursor -= 16;
            }
            cursor -= 8;
        }

        void record(int index, List<Object> values) throws IOException {
            float estimatedHeight = 31;
            for (int column = 0; column < report.columns().size(); column++) {
                estimatedHeight += fieldLines(report.columns().get(column).label(),
                        ReportExportService.display(values.get(column))) * LEADING + 4;
            }
            float fullPageCapacity = PDRectangle.A4.getHeight() - MARGIN - BOTTOM - 64;
            if (cursor - Math.min(estimatedHeight, 61) < BOTTOM
                    || (estimatedHeight <= fullPageCapacity && cursor - estimatedHeight < BOTTOM)) {
                newPage(false);
            }
            band("记录 " + index);
            for (int column = 0; column < report.columns().size(); column++) {
                field(report.columns().get(column).label(), ReportExportService.display(values.get(column)), index);
            }
            gap(9);
        }

        void band(String heading) throws IOException {
            stream.setNonStrokingColor(PALE);
            stream.addRect(MARGIN, cursor - 24, width, 24);
            stream.fill();
            text(stream, font, heading, MARGIN + 9, cursor - 16, 10, ACCENT);
            cursor -= 32;
        }

        void field(String label, String value, int recordIndex) throws IOException {
            List<String> labels = wrap(label, TEXT_SIZE, LABEL_WIDTH - 10);
            List<String> values = wrap(value, TEXT_SIZE, width - LABEL_WIDTH);
            int lines = Math.max(labels.size(), values.size());
            for (int line = 0; line < lines; line++) {
                if (cursor - LEADING < BOTTOM) {
                    newPage(false);
                    if (recordIndex > 0) {
                        band("记录 " + recordIndex + "（续）");
                    }
                }
                String left = line < labels.size() ? labels.get(line) : "";
                String right = line < values.size() ? values.get(line) : "";
                text(stream, font, left, MARGIN + 2, cursor - TEXT_SIZE, TEXT_SIZE, MUTED);
                text(stream, font, right, MARGIN + LABEL_WIDTH, cursor - TEXT_SIZE, TEXT_SIZE, INK);
                cursor -= LEADING;
            }
            cursor -= 4;
        }

        private int fieldLines(String label, String value) throws IOException {
            return Math.max(wrap(label, TEXT_SIZE, LABEL_WIDTH - 10).size(),
                    wrap(value, TEXT_SIZE, width - LABEL_WIDTH).size());
        }

        void gap(float size) {
            cursor -= size;
        }

        void note(String value) throws IOException {
            for (String line : wrap(value, 8, width)) {
                if (cursor - 13 < BOTTOM) {
                    newPage(false);
                }
                text(stream, font, line, MARGIN, cursor - 8, 8, MUTED);
                cursor -= 13;
            }
        }

        private List<String> wrap(String source, float size, float maxWidth) throws IOException {
            String safe = printable(source);
            List<String> result = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            float lineWidth = 0;
            for (int offset = 0; offset < safe.length();) {
                int codePoint = safe.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    result.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                    continue;
                }
                String character = new String(Character.toChars(codePoint));
                float characterWidth = font.getStringWidth(character) / 1000 * size;
                if (lineWidth + characterWidth > maxWidth && !line.isEmpty()) {
                    result.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                }
                line.append(character);
                lineWidth += characterWidth;
            }
            result.add(line.toString());
            return result;
        }

        private String printable(String value) throws IOException {
            String normalized = value.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
            StringBuilder safe = new StringBuilder();
            for (int offset = 0; offset < normalized.length();) {
                int point = normalized.codePointAt(offset);
                offset += Character.charCount(point);
                if (point == '\n') {
                    safe.append('\n');
                    continue;
                }
                String cached = glyphs.get(point);
                if (cached == null) {
                    String character = new String(Character.toChars(point));
                    try {
                        font.encode(character);
                        cached = character;
                    } catch (IllegalArgumentException e) {
                        cached = "[U+" + Integer.toHexString(point).toUpperCase(java.util.Locale.ROOT) + "]";
                    }
                    glyphs.put(point, cached);
                }
                safe.append(cached);
            }
            return safe.toString();
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
