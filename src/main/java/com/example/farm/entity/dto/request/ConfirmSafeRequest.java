package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 现场确认机器已清理安全请求 DTO
 */
@Data
@Schema(description = "确认机器安全请求参数")
public class ConfirmSafeRequest {

    @Schema(description = "打印机ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long printerId;

    @Schema(description = "操作员ID（从安全上下文获取，可选）")
    private Long operatorId;
}