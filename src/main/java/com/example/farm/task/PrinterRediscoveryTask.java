package com.example.farm.task;

import com.example.farm.service.PrinterRediscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 开机后的初次发现和低频身份复核，不依赖浏览器，也不派发任务。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"farm.discovery.enabled", "farm.monitor.enabled"}, havingValue = "true")
public class PrinterRediscoveryTask {
    private final PrinterRediscoveryService rediscoveryService;

    @Scheduled(fixedDelayString = "${farm.discovery.interval:5m}",
            initialDelayString = "${farm.discovery.initial-delay:10s}")
    public void rediscoverKnownDevices() {
        rediscoveryService.startAutomatic();
    }
}
