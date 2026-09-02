package com.example.farm.protocol;

import java.math.BigDecimal;

/**
 * 协议适配器输出的统一设备状态。
 */
public record PrinterDeviceStatus(
        PrinterStatus status,
        String rawState,
        String systemMessage,
        String filename,
        BigDecimal progress,
        BigDecimal toolTemperature,
        BigDecimal toolTarget,
        BigDecimal bedTemperature,
        BigDecimal bedTarget,
        BigDecimal printDuration,
        BigDecimal totalDuration,
        BigDecimal filamentUsed
) {
}
