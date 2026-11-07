package com.example.farm.protocol.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Public, firmware-independent slicing metadata. Device paths stay outside this object. */
public record GCodeFileMetadata(
        int schemaVersion, String fileId, String fileName, Long sizeBytes, String sha256,
        String source, Instant parsedAt, String slicerName, String slicerVersion,
        Long estimatedPrintSeconds, Long totalLayers, BigDecimal layerHeightMm,
        BigDecimal firstLayerHeightMm, BigDecimal nozzleDiameterMm, BigDecimal objectHeightMm,
        Integer nozzleTemperatureC, Integer firstLayerNozzleTemperatureC,
        Integer bedTemperatureC, Integer firstLayerBedTemperatureC,
        List<Filament> filaments, List<Thumbnail> thumbnails) {

    public GCodeFileMetadata {
        filaments = List.copyOf(filaments);
        thumbnails = List.copyOf(thumbnails);
    }

    public record Filament(String toolId, String material, String color,
                           BigDecimal lengthMm, BigDecimal weightGrams) { }

    public record Thumbnail(String sha256, String contentType, int width, int height, int sizeBytes) { }
}
