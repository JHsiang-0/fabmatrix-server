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
        BigDecimal filamentUsed
) {
}
