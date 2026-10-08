package com.example.farm.common.export;

import java.util.Objects;

public record ExportDocument(String filename, String contentType, byte[] content) {
    public ExportDocument {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
