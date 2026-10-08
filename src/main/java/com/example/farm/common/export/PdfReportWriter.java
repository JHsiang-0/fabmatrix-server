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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Landscape record table with repeated headers and complete long-text appendices. */
final class PdfReportWriter {
    private static final String FONT_RESOURCE = "/export/fonts/NotoSansSC-Regular.ttf";
    private static final PDRectangle PAGE_SIZE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = 24;
    private static final float BOTTOM = 40;
    private static final float PADDING = 3;
    private static final float TEXT_SIZE = 8.3f;
    private static final float LEADING = 10.2f;
    private static final Color INK = new Color(30, 45, 60);
    private static final Color MUTED = new Color(91, 108, 121);
    private static final Color ACCENT = new Color(18, 91, 105);
    private static final Color PALE = new Color(239, 246, 248);
    private static final Color GRID = new Color(197, 212, 219);

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
                layout.summary();
                layout.startTable();
                if (report.rows().isEmpty()) {
                    layout.emptyTable();
                } else {
                    for (int index = 0; index < report.rows().size(); index++) {
                        layout.record(index + 1, report.rows().get(index));
                    }
                }
                layout.appendices();
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
                stream.setStrokingColor(GRID);
                stream.moveTo(MARGIN, 28);
                stream.lineTo(page.getMediaBox().getWidth() - MARGIN, 28);
                stream.stroke();
                text(stream, font, "FabMatrix · 空值 - · 缺字 [U+编码] · 长文本见附录 · 时间按报告注明的时区显示",
                        MARGIN, 14, 7.3f, MUTED);
                text(stream, font, numbering, page.getMediaBox().getWidth() - MARGIN - width, 14, 8, MUTED);
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

    private record Appendix(int number, int row, int column, String value) {
    }

    private static final class Layout implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private final ExportReport report;
        private final float width = PAGE_SIZE.getWidth() - MARGIN * 2;
        private final Map<Integer, String> glyphs = new HashMap<>();
        private final List<Appendix> appendices = new ArrayList<>();
        private final float[] columnWidths;
        private final List<List<String>> headings;
        private final float headerHeight;
        private PDPageContentStream stream;
        private float cursor;

        private Layout(PDDocument document, PDType0Font font, ExportReport report) throws IOException {
            this.document = document;
            this.font = font;
            this.report = report;
            columnWidths = allocateWidths();
            headings = new ArrayList<>();
            for (int index = 0; index < report.columns().size(); index++) {
                headings.add(wrap(report.columns().get(index).label(), TEXT_SIZE, columnWidths[index] - PADDING * 2));
            }
            headerHeight = maximumLines(headings) * LEADING + PADDING * 2;
        }

        void newPage(boolean first) throws IOException {
            close();
            PDPage page = new PDPage(PAGE_SIZE);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            cursor = PAGE_SIZE.getHeight() - MARGIN;
            float titleSize = first ? 16 : 10;
            for (String line : wrap(report.title(), titleSize, width)) {
                text(stream, font, line, MARGIN, cursor - titleSize, titleSize, INK);
                cursor -= titleSize + 4;
            }
            if (first) {
                text(stream, font, "导出时间：" + ReportExportService.display(report.generatedAt()),
                        MARGIN, cursor - 8, 8, MUTED);
                cursor -= 15;
            }
            stream.setStrokingColor(ACCENT);
            stream.setLineWidth(1);
            stream.moveTo(MARGIN, cursor - 3);
            stream.lineTo(MARGIN + width, cursor - 3);
            stream.stroke();
            cursor -= 12;
        }

        void summary() throws IOException {
            if (report.summary().isEmpty()) {
                return;
            }
            text(stream, font, "查询与统计", MARGIN, cursor - 9.5f, 9.5f, ACCENT);
            cursor -= 16;
            float halfWidth = (width - 16) / 2;
            for (int index = 0; index < report.summary().size(); index += 2) {
                List<List<String>> pair = new ArrayList<>();
                for (int entry = index; entry < Math.min(index + 2, report.summary().size()); entry++) {
                    var item = report.summary().get(entry);
                    pair.add(wrap(item.label() + "：" + ReportExportService.display(item.value()), TEXT_SIZE, halfWidth));
                }
                int lines = maximumLines(pair);
                for (int line = 0; line < lines; line++) {
                    if (cursor - LEADING < BOTTOM) {
                        newPage(false);
                    }
                    for (int entry = 0; entry < pair.size(); entry++) {
                        if (line < pair.get(entry).size()) {
                            text(stream, font, pair.get(entry).get(line), MARGIN + entry * (halfWidth + 16),
                                    cursor - TEXT_SIZE, TEXT_SIZE, MUTED);
                        }
                    }
                    cursor -= LEADING;
                }
                cursor -= 3;
            }
            cursor -= 5;
        }

        void startTable() throws IOException {
            if (cursor - headerHeight - 40 < BOTTOM) {
                newPage(false);
            }
            text(stream, font, "历史明细 · " + report.rows().size() + " 条记录", MARGIN,
                    cursor - 9.5f, 9.5f, ACCENT);
            cursor -= 17;
            tableHeader();
        }

        private void tableHeader() throws IOException {
            drawCells(headings, 0, maximumLines(headings), headerHeight, ACCENT, Color.WHITE);
        }

        private void newTablePage() throws IOException {
            newPage(false);
            tableHeader();
        }

        void emptyTable() throws IOException {
            stream.setNonStrokingColor(PALE);
            stream.addRect(MARGIN, cursor - 24, width, 24);
            stream.fill();
            border(MARGIN, cursor - 24, width, 24);
            text(stream, font, "没有符合条件的历史记录", MARGIN + PADDING, cursor - 15, TEXT_SIZE, MUTED);
            cursor -= 24;
        }

        void record(int index, List<Object> values) throws IOException {
            List<List<String>> cells = new ArrayList<>();
            for (int column = 0; column < values.size(); column++) {
                Object value = values.get(column);
                String display = ReportExportService.display(value);
                if (value instanceof LocalDateTime) {
                    display = display.replace(' ', '\n');
                }
                List<String> lines = wrap(display, TEXT_SIZE, columnWidths[column] - PADDING * 2);
                // Preserve numeric identifiers in the table. Other extremely long cells get
                // an explicit reference and their complete text in a full-width appendix.
                if (lines.size() > 6 && !(value instanceof LocalDateTime)
                        && !display.matches("[+-]?[0-9]+")) {
                    int number = appendices.size() + 1;
                    appendices.add(new Appendix(number, index, column, display));
                    List<String> preview = new ArrayList<>(lines.subList(0, 2));
                    preview.addAll(wrap("见附录 A" + number, TEXT_SIZE, columnWidths[column] - PADDING * 2));
                    cells.add(preview);
                } else {
                    cells.add(lines);
                }
            }
            int totalLines = maximumLines(cells);
            float wholeHeight = totalLines * LEADING + PADDING * 2;
            // Normal rows stay together. Truly long numeric cells consume remaining space
            // and continue under a repeated header rather than leaving blank pages.
            if (totalLines <= 6 && cursor - wholeHeight < BOTTOM) {
                newTablePage();
            }
            int offset = 0;
            while (offset < totalLines) {
                int availableLines = (int) Math.floor((cursor - BOTTOM - PADDING * 2) / LEADING);
                if (availableLines < 1) {
                    newTablePage();
                    if (offset > 0) continuation("记录 " + index);
                    availableLines = (int) Math.floor((cursor - BOTTOM - PADDING * 2) / LEADING);
                }
                int segmentLines = Math.min(totalLines - offset, availableLines);
                float height = segmentLines * LEADING + PADDING * 2;
                drawCells(cells, offset, segmentLines, height, index % 2 == 0 ? PALE : Color.WHITE, INK);
                offset += segmentLines;
                if (offset < totalLines) {
                    newTablePage();
                    continuation("记录 " + index);
                }
            }
        }

        void appendices() throws IOException {
            if (appendices.isEmpty()) return;
            if (cursor - 60 < BOTTOM) newPage(false);
            cursor -= 8;
            text(stream, font, "长文本附录", MARGIN, cursor - 10, 10, ACCENT);
            cursor -= 19;
            for (Appendix appendix : appendices) {
                String reference = "附录 A" + appendix.number();
                String context = reference + " · 记录 " + appendix.row() + " · "
                        + report.columns().get(appendix.column()).label() + " · "
                        + report.columns().getFirst().label() + "："
                        + ReportExportService.display(report.rows().get(appendix.row() - 1).getFirst());
                List<String> labels = wrap(context, 9, width);
                if (cursor - labels.size() * 13 - LEADING < BOTTOM) newPage(false);
                for (String label : labels) {
                    if (cursor - 13 < BOTTOM) newPage(false);
                    text(stream, font, label, MARGIN, cursor - 9, 9, ACCENT);
                    cursor -= 13;
                }
                for (String line : wrap(appendix.value(), TEXT_SIZE, width)) {
                    if (cursor - LEADING < BOTTOM) {
                        newPage(false);
                        continuation(reference);
                    }
                    text(stream, font, line, MARGIN, cursor - TEXT_SIZE, TEXT_SIZE, INK);
                    cursor -= LEADING;
                }
                cursor -= 9;
            }
        }

        private void continuation(String reference) throws IOException {
            text(stream, font, reference + "（续）", MARGIN, cursor - 8, 8, ACCENT);
            cursor -= 15;
        }

        private void drawCells(List<List<String>> cells, int offset, int lines, float height,
                               Color background, Color foreground) throws IOException {
            float x = MARGIN;
            for (int column = 0; column < cells.size(); column++) {
                float columnWidth = columnWidths[column];
                stream.setNonStrokingColor(background);
                stream.addRect(x, cursor - height, columnWidth, height);
                stream.fill();
                border(x, cursor - height, columnWidth, height);
                List<String> cell = cells.get(column);
                for (int line = 0; line < lines && offset + line < cell.size(); line++) {
                    text(stream, font, cell.get(offset + line), x + PADDING,
                            cursor - PADDING - TEXT_SIZE - line * LEADING, TEXT_SIZE, foreground);
                }
                x += columnWidth;
            }
            cursor -= height;
        }

        private void border(float x, float y, float boxWidth, float height) throws IOException {
            stream.setStrokingColor(GRID);
            stream.setLineWidth(0.35f);
            stream.addRect(x, y, boxWidth, height);
            stream.stroke();
        }

        private int maximumLines(List<List<String>> cells) {
            return cells.stream().mapToInt(List::size).max().orElse(1);
        }

        private float[] allocateWidths() throws IOException {
            int count = report.columns().size();
            float[] minimums = new float[count];
            java.util.Arrays.fill(minimums, TEXT_SIZE + PADDING * 2 + 0.2f);
            for (List<Object> row : report.rows()) {
                for (int index = 0; index < count; index++) {
                    Object value = row.get(index);
                    if (value instanceof LocalDateTime dateTime) {
                        String date = dateTime.toLocalDate().toString();
                        minimums[index] = Math.max(minimums[index], font.getStringWidth(date) / 1000 * TEXT_SIZE + PADDING * 2 + 1);
                    } else if (value != null) {
                        String candidate = value.toString();
                        if (candidate.length() <= 30 && candidate.matches("[+-]?[0-9]{15,}")) {
                            minimums[index] = Math.max(minimums[index], font.getStringWidth(candidate) / 1000 * TEXT_SIZE + PADDING * 2 + 1);
                        }
                    }
                }
            }
            float minimumTotal = 0;
            for (float minimum : minimums) minimumTotal += minimum;
            if (minimumTotal > width) {
                // Very wide generic reports still preserve every character through wrapping.
                java.util.Arrays.fill(minimums, TEXT_SIZE + PADDING * 2 + 0.2f);
            }
            float remainingWidth = width;
            int remainingWeight = report.columns().stream().mapToInt(ExportReport.Column::width).sum();
            float[] allocated = new float[count];
            boolean[] fixed = new boolean[count];
            boolean changed;
            do {
                changed = false;
                for (int index = 0; index < count; index++) {
                    if (!fixed[index] && report.columns().get(index).width() * remainingWidth / remainingWeight < minimums[index]) {
                        fixed[index] = true;
                        allocated[index] = minimums[index];
                        remainingWidth -= allocated[index];
                        remainingWeight -= report.columns().get(index).width();
                        changed = true;
                    }
                }
            } while (changed);
            for (int index = 0; index < count; index++) {
                if (!fixed[index]) {
                    allocated[index] = report.columns().get(index).width() * remainingWidth / remainingWeight;
                }
            }
            float preceding = 0;
            for (int index = 0; index < count - 1; index++) preceding += allocated[index];
            allocated[count - 1] = width - preceding;
            return allocated;
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
