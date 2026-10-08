package com.example.farm.common.export;

import com.example.farm.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

@Service
public class ReportExportService {
    public static final int MAX_DOCUMENT_BYTES = 20 * 1024 * 1024;
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ExportDocument render(ExportReport report, ExportFormat format, String basename) {
        return render(report, format, basename, MAX_DOCUMENT_BYTES);
    }

    // Smaller package-private budgets let tests verify the real writers without allocating 20 MiB.
    ExportDocument render(ExportReport report, ExportFormat format, String basename, int documentByteLimit) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(format, "format");
        if (documentByteLimit < 1 || documentByteLimit > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("导出文件容量无效");
        }
        if (basename == null || basename.isBlank() || basename.length() > 160
                || basename.codePoints().anyMatch(c -> Character.isISOControl(c) || "/\\:*?\"<>|".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("导出文件名无效");
        }
        try {
            LimitedOutputStream output = new LimitedOutputStream(documentByteLimit);
            switch (format) {
                case XLSX -> new ExcelReportWriter().write(report, output);
                case PDF -> new PdfReportWriter().write(report, output);
            }
            return new ExportDocument(basename + "." + format.extension(), format.contentType(), output.toByteArray());
        } catch (IOException | RuntimeException e) {
            // POI's ZIP/OPC writers can wrap an OutputStream failure in a runtime exception.
            Throwable cause = e;
            for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
                if (cause instanceof DocumentTooLargeException) {
                    throw new BusinessException(422, "导出文件超过 20 MiB，请缩小查询范围");
                }
            }
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(500, "生成导出文件失败，请重试");
        }
    }

    static String display(Object value) {
        if (value == null) {
            return "-";
        }
        return value instanceof LocalDateTime dateTime ? DATE_TIME.format(dateTime) : value.toString();
    }

    /** Enforces the response budget while writers are emitting, before buffer growth. */
    static final class LimitedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final int limit;

        LimitedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, value.length);
            requireCapacity(length);
            delegate.write(value, offset, length);
        }

        private void requireCapacity(int added) throws DocumentTooLargeException {
            if (added > limit - delegate.size()) {
                throw new DocumentTooLargeException();
            }
        }

        byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    static final class DocumentTooLargeException extends IOException {
    }
}
