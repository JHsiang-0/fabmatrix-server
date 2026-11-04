package com.example.farm.entity.vo;

import com.example.farm.protocol.model.CanonicalPrinterModel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/** 本地只读统一模型。采样时间属于 model，serverTime 用于客户端校准新鲜度。 */
public record PrinterModelVO(Instant serverTime, boolean stale,
                             @Schema(implementation = CanonicalPrinterModel.class, nullable = true)
                             Map<String, Object> model) { }
