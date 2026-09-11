package com.example.farm.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * Farm 的 LocalDateTime 契约使用农场 wall-clock，不携带 UTC 偏移。
 * 统一 JVM 默认时区，确保 LocalDateTime.now() 与数据库 local datetime 约定一致。
 */
@Configuration
public class FarmTimeZoneConfig {

    public static final String DEFAULT_ZONE_ID = "Asia/Shanghai";

    @PostConstruct
    void configureDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(DEFAULT_ZONE_ID));
    }

    ZoneId zoneId() {
        return ZoneId.of(DEFAULT_ZONE_ID);
    }
}
