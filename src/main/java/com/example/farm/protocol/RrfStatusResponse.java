package com.example.farm.protocol;

import java.math.BigDecimal;

/**
 * RRF 客户端解析后的最小状态对象。
 *
 * <p>字段对应 RRF 对象模型或状态响应，具体 JSON 解析留在 RrfApiClient。</p>
 */
public record RrfStatusResponse(
        String stateStatus,
        String statusCode,
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

    /** 兼容已有协议单元测试和旧调用方的构造函数。 */
    public RrfStatusResponse(String stateStatus, String statusCode, String filename,
                             BigDecimal progress, BigDecimal toolTemperature, BigDecimal toolTarget,
                             BigDecimal bedTemperature, BigDecimal bedTarget, BigDecimal printDuration,
                             BigDecimal totalDuration, BigDecimal filamentUsed) {
        this(stateStatus, statusCode, filename, progress, toolTemperature, toolTarget,
                bedTemperature, bedTarget, printDuration, totalDuration, filamentUsed,
                null, null, null, null, null);
    }
}
