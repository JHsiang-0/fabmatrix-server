package com.example.farm.protocol.file;

import com.example.farm.protocol.model.GCodeFileMetadata;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/** Internal inspection result, including bounded image bytes and an optional layer index. */
public record GCodeFileInspection(GCodeFileMetadata metadata, List<ThumbnailImage> thumbnails,
                                 List<LayerMark> layers) {
    public GCodeFileInspection {
        thumbnails = List.copyOf(thumbnails);
        layers = List.copyOf(layers);
    }

    public record ThumbnailImage(GCodeFileMetadata.Thumbnail descriptor, @JsonIgnore byte[] png) {
        public ThumbnailImage { png = png.clone(); }
        @Override @JsonIgnore public byte[] png() { return png.clone(); }
    }

    public record LayerMark(long offset, long layer) { }

    /** The file cursor is an estimate of queued execution, so callers retain its derived source. */
    public Long currentLayerAt(long position) {
        if (position <= 0 || layers.isEmpty() || metadata.sizeBytes() == null || position > metadata.sizeBytes()) return null;
        int low = 0;
        int high = layers.size() - 1;
        Long current = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            LayerMark mark = layers.get(middle);
            if (mark.offset() <= position) {
                current = mark.layer() > 0 ? mark.layer() : null;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return current;
    }
}
