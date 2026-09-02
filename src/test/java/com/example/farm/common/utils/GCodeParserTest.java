package com.example.farm.common.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GCodeParserTest {

    @Test
    void keepsStandardMillimetersRawAndExposesLegacyLengthInMeters() {
        GCodeParser.GCodeMeta meta = GCodeParser.parseMetadata(
                "; filament used [mm] = 500\n"
                        + "; filament used [g] = 1.2\n");

        assertThat(meta.getFilamentUsedMM()).isEqualByComparingTo("500");
        assertThat(meta.getFilamentLength()).isNull();
    }

    @Test
    void convertsShortLegacyMillimetersWithoutHeuristicThreshold() {
        GCodeParser.GCodeMeta meta = GCodeParser.parseMetadata("; filament used: 500 mm\n");

        assertThat(meta.getFilamentUsedMM()).isEqualByComparingTo("500");
        assertThat(meta.getFilamentLength()).isEqualByComparingTo("0.50");
    }

    @Test
    void preservesExplicitLegacyMeters() {
        GCodeParser.GCodeMeta meta = GCodeParser.parseMetadata("; filament used: 3 m\n");

        assertThat(meta.getFilamentUsedMM()).isNull();
        assertThat(meta.getFilamentLength()).isEqualByComparingTo("3");
    }
}
