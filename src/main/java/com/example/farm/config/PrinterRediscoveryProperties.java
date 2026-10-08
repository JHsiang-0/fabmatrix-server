package com.example.farm.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 已登记设备的地址恢复；独立于后台任务派单。 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "farm.discovery")
public class PrinterRediscoveryProperties {
    private boolean enabled = false;
    @Size(max = 8)
    private List<String> subnets = new ArrayList<>();
    private Duration interval = Duration.ofMinutes(5);
    private Duration initialDelay = Duration.ofSeconds(10);
    private Duration timeout = Duration.ofMinutes(2);
    @Min(1)
    @Max(64)
    private int concurrency = 16;
}
