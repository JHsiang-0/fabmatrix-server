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

    @Test
    void acceptsExactDecimalTemperaturesAndPreservesTimeAndLayerParsing() {
        var meta = GCodeParser.parseMetadata("; nozzle_temperature = 245.0\r\n"
                + "; nozzle_temperature_initial_layer = 250.00\r\n"
                + "; bed_temperature = 45.0\r\n; first_layer_bed_temperature = 50.00\r\n"
                + "; TIME:120\r\n; total layer number: 12\r\n");
        assertThat(meta.getNozzleTemp()).isEqualTo(245);
        assertThat(meta.getFirstLayerNozzleTemp()).isEqualTo(250);
        assertThat(meta.getBedTemp()).isEqualTo(45);
        assertThat(meta.getFirstLayerBedTemp()).isEqualTo(50);
        assertThat(meta.getEstimatedPrintTimeSeconds()).isEqualTo(120);
        assertThat(meta.getTotalLayers()).isEqualTo(12);
    }

    @Test
    void acceptsBoundariesAndOnlyCommonTemperaturesFromBoundedLists() {
        assertTemperatureValues("0", 0);
        assertTemperatureValues("0.0", 0);
        assertTemperatureValues("-0.0", 0);
        assertTemperatureValues("500.0", 500);
        for (String value : new String[]{"245", "245.000", "2.45E2", " [245.00, 245] ", "245;245.0",
                "[245,245,245,245,245,245,245,245]"}) {
            assertTemperatureValues(value, 245);
        }
    }

    @Test
    void rejectsFractionalMalformedOutOfRangeAndAmbiguousTemperaturesWithoutPrefixTruncation() {
        for (String value : new String[]{"", "245.5", "245.000000000000000001", "245abc", "245 C", "245 245",
                "245,250", "[245,250]", "245;250", "[0,245]", "245,", "245;;245", "[]", "[245", "245]",
                "[[245]]", "\"245\"", "[245,\"245\"]", "[245,null]", "null", "NaN", "Infinity", "-1",
                "500.000000000000000001", "501", "2147483648", "[245,245,245,245,245,245,245,245,245]"}) {
            assertTemperatureValues(value, null);
        }
    }

    @Test
    void appliesWholeValueValidationToEveryOrcaAndFirstLayerAlias() {
        for (String value : new String[]{"245.0", "245.5", "245abc", "[245,250]"}) {
            Integer expected = value.equals("245.0") ? 245 : null;
            var alternate = GCodeParser.parseMetadata("; nozzle_temperature_first_layer = " + value
                    + "\n; bed_temperature_first_layer = " + value + "\n");
            assertThat(alternate.getFirstLayerNozzleTemp()).as("first-layer nozzle %s", value).isEqualTo(expected);
            assertThat(alternate.getFirstLayerBedTemp()).as("first-layer bed %s", value).isEqualTo(expected);
            var orca = GCodeParser.parseMetadata("; first_layer_temperature = " + value
                    + "\n; hot_plate_temp = " + value + "\n; hot_plate_temp_initial_layer = " + value + "\n");
            assertThat(orca.getFirstLayerNozzleTemp()).as("Orca first-layer nozzle %s", value).isEqualTo(expected);
            assertThat(orca.getBedTemp()).as("Orca normal bed %s", value).isEqualTo(expected);
            assertThat(orca.getFirstLayerBedTemp()).as("Orca first-layer bed %s", value).isEqualTo(expected);
        }
    }

    @Test
    void preservesExplicitAliasPriorityAndKeepsNormalLayerTemperatureIndependent() {
        var meta = GCodeParser.parseMetadata("; nozzle_temperature_initial_layer = 245.0\n"
                + "; nozzle_temperature_first_layer = 250.0\n; first_layer_temperature = 255.0\n"
                + "; first_layer_bed_temperature = 45.0\n; bed_temperature_first_layer = 50.0\n"
                + "; hot_plate_temp_initial_layer = 55.0\n");
        assertThat(meta.getFirstLayerNozzleTemp()).isEqualTo(245);
        assertThat(meta.getFirstLayerBedTemp()).isEqualTo(45);
        assertThat(meta.getNozzleTemp()).isNull();
        assertThat(meta.getBedTemp()).isNull();
    }

    @Test
    void missingTemperatureCannotConsumeANumberFromTheFollowingLine() {
        var meta = GCodeParser.parseMetadata("; nozzle_temperature =\n245\n"
                + "; nozzle_temperature_initial_layer =\r\n246\r\n"
                + "; bed_temperature =\n247\n; first_layer_bed_temperature =\n248\n");
        assertThat(meta.getNozzleTemp()).isNull();
        assertThat(meta.getFirstLayerNozzleTemp()).isNull();
        assertThat(meta.getBedTemp()).isNull();
        assertThat(meta.getFirstLayerBedTemp()).isNull();
    }

    private static void assertTemperatureValues(String value, Integer expected) {
        var meta = GCodeParser.parseMetadata("; nozzle_temperature = " + value
                + "\n; nozzle_temperature_initial_layer = " + value
                + "\n; bed_temperature = " + value + "\n; first_layer_bed_temperature = " + value + "\n");
        assertThat(meta.getNozzleTemp()).as("normal nozzle %s", value).isEqualTo(expected);
        assertThat(meta.getFirstLayerNozzleTemp()).as("first-layer nozzle %s", value).isEqualTo(expected);
        assertThat(meta.getBedTemp()).as("normal bed %s", value).isEqualTo(expected);
        assertThat(meta.getFirstLayerBedTemp()).as("first-layer bed %s", value).isEqualTo(expected);
    }
}
