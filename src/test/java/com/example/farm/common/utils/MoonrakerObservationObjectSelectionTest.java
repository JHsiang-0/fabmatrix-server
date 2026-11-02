package com.example.farm.common.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoonrakerObservationObjectSelectionTest {
    @Test
    void selectsSupportedKlipperObjectsForCanonicalObservation() {
        for (String name : new String[]{"webhooks", "print_stats", "display_status", "extruder1",
                "heater_generic chamber", "fan_generic part", "heater_fan hotend",
                "controller_fan board", "temperature_sensor chamber", "gcode_move", "toolhead",
                "virtual_sdcard", "filament_switch_sensor runout", "filament_motion_sensor encoder"}) {
            assertThat(MoonrakerApiClient.isObservationObject(name)).as(name).isTrue();
        }
    }

    @Test
    void excludesUnrelatedAndUnboundedObjectNames() {
        for (String name : new String[]{"camera", "file_manager", "mcu", "", null, "x".repeat(129)}) {
            assertThat(MoonrakerApiClient.isObservationObject(name)).as(String.valueOf(name)).isFalse();
        }
    }
}
