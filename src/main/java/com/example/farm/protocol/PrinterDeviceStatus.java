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
        BigDecimal filamentUsed,
        BigDecimal filePosition,
        BigDecimal fileSize,
        BigDecimal timesLeft,
        Boolean lastFileCancelled,
        Boolean lastFileAborted
) {

    /** 兼容 Klipper 及现有测试的基础状态构造函数。 */
    public PrinterDeviceStatus(PrinterStatus status, String rawState, String systemMessage,
                               String filename, BigDecimal progress, BigDecimal toolTemperature,
                               BigDecimal toolTarget, BigDecimal bedTemperature, BigDecimal bedTarget,
                               BigDecimal printDuration, BigDecimal totalDuration, BigDecimal filamentUsed) {
        this(status, rawState, systemMessage, filename, progress, toolTemperature, toolTarget,
                bedTemperature, bedTarget, printDuration, totalDuration, filamentUsed,
                null, null, null, null, null);
    }
}
