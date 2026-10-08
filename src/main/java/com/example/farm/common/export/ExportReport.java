package com.example.farm.common.export;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Business services supply authorized data; renderers have no entity or query dependency. */
public record ExportReport(String title, LocalDateTime generatedAt, List<SummaryEntry> summary,
                           List<Column> columns, List<List<Object>> rows) {

    public ExportReport {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(generatedAt, "generatedAt");
        summary = List.copyOf(summary);
        columns = List.copyOf(columns);
        // List.copyOf rejects null cells, but a report must preserve absent values.
        rows = rows.stream().map(row -> Collections.unmodifiableList(new ArrayList<>(row))).toList();
        if (title.isBlank() || columns.isEmpty() || columns.size() > 50) {
            throw new IllegalArgumentException("报表标题及列定义无效");
        }
        for (List<Object> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("报表行与列定义不一致");
            }
        }
    }

    public record SummaryEntry(String label, Object value) {
        public SummaryEntry {
            Objects.requireNonNull(label, "label");
        }
    }

    /** Width uses the approximate number of Latin characters, as in spreadsheet columns. */
    public record Column(String label, int width) {
        public Column {
            Objects.requireNonNull(label, "label");
            if (width < 8 || width > 100) {
                throw new IllegalArgumentException("报表列宽必须介于 8 至 100");
            }
        }
    }
}
