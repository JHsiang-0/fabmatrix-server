package com.example.farm.protocol;

import com.example.farm.protocol.observation.PrinterObservation;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
        Boolean lastFileAborted,
        BigDecimal lastDuration,
        BigDecimal deviceUptime,
        @JsonIgnore PrinterObservation observation
) {

    /** 仅保留旧协议字段，用于状态变化去重，忽略每次不同的采集时间。 */
    public PrinterDeviceStatus withoutObservation() {
        return new PrinterDeviceStatus(status, rawState, systemMessage, filename, progress,
                toolTemperature, toolTarget, bedTemperature, bedTarget, printDuration, totalDuration,
                filamentUsed, filePosition, fileSize, timesLeft, lastFileCancelled, lastFileAborted,
                lastDuration, deviceUptime);
    }

    public PrinterDeviceStatus(PrinterStatus status, String rawState, String systemMessage,
                               String filename, BigDecimal progress, BigDecimal toolTemperature,
                               BigDecimal toolTarget, BigDecimal bedTemperature, BigDecimal bedTarget,
                               BigDecimal printDuration, BigDecimal totalDuration, BigDecimal filamentUsed,
                               BigDecimal filePosition, BigDecimal fileSize, BigDecimal timesLeft,
                               Boolean lastFileCancelled, Boolean lastFileAborted, BigDecimal lastDuration,
                               BigDecimal deviceUptime) {
        this(status, rawState, systemMessage, filename, progress, toolTemperature, toolTarget,
                bedTemperature, bedTarget, printDuration, totalDuration, filamentUsed, filePosition,
                fileSize, timesLeft, lastFileCancelled, lastFileAborted, lastDuration, deviceUptime, null);
    }

    /** 兼容未提供设备 uptime 的旧完整构造函数。 */
    public PrinterDeviceStatus(PrinterStatus status, String rawState, String systemMessage,
                               String filename, BigDecimal progress, BigDecimal toolTemperature,
                               BigDecimal toolTarget, BigDecimal bedTemperature, BigDecimal bedTarget,
                               BigDecimal printDuration, BigDecimal totalDuration, BigDecimal filamentUsed,
                               BigDecimal filePosition, BigDecimal fileSize, BigDecimal timesLeft,
                               Boolean lastFileCancelled, Boolean lastFileAborted) {
        this(status, rawState, systemMessage, filename, progress, toolTemperature, toolTarget,
                bedTemperature, bedTarget, printDuration, totalDuration, filamentUsed,
                filePosition, fileSize, timesLeft, lastFileCancelled, lastFileAborted, null, null);
    }

    /** 兼容 Klipper 及现有测试的基础状态构造函数。 */
    public PrinterDeviceStatus(PrinterStatus status, String rawState, String systemMessage,
                               String filename, BigDecimal progress, BigDecimal toolTemperature,
                               BigDecimal toolTarget, BigDecimal bedTemperature, BigDecimal bedTarget,
                               BigDecimal printDuration, BigDecimal totalDuration, BigDecimal filamentUsed) {
        this(status, rawState, systemMessage, filename, progress, toolTemperature, toolTarget,
                bedTemperature, bedTarget, printDuration, totalDuration, filamentUsed,
                null, null, null, null, null, null, null);
    }
}
