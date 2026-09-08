package com.example.farm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 打印机监控运行参数；设备是否纳入监控由 farm_printer.monitor_enabled 决定。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "farm.monitor")
public class PrinterMonitorProperties {

    private boolean enabled = false;
    /** 历史兼容配置；配置非空时仅巡检这些 ID，迁移后建议清空。 */
    private List<Long> printerIds = new ArrayList<>();
    private Duration interval = Duration.ofSeconds(5);
    private int concurrency = 10;
    /** 连续失败达到阈值后才把通信状态标记为 OFFLINE。 */
    private int failureThreshold = 3;
    /** 连续成功达到阈值后才从 OFFLINE 恢复 ONLINE。 */
    private int recoverySuccessThreshold = 2;
    /** 空闲设备的轮询间隔。 */
    private Duration idleInterval = Duration.ofSeconds(15);
    /** 已确认通信离线设备的轮询间隔。 */
    private Duration offlineInterval = Duration.ofSeconds(30);
}
