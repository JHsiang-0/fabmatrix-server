package com.example.farm.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class FarmTimeZoneConfigTest {

    @Test
    void configuresAsiaShanghaiAsTheLocalDateTimeWallClock() {
        TimeZone original = TimeZone.getDefault();
        try {
            FarmTimeZoneConfig config = new FarmTimeZoneConfig();
            config.configureDefaultTimeZone();

            assertThat(config.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
            assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
