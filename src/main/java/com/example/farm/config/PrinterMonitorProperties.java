package com.example.farm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** v2 打印机监控配置。空白名单表示不监控任何设备。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "farm.monitor")
public class PrinterMonitorProperties {

    private boolean enabled = false;
    private List<Long> printerIds = new ArrayList<>();
    private Duration interval = Duration.ofSeconds(5);
    private int concurrency = 10;
}
