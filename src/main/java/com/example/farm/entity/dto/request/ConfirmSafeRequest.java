package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 现场确认机器已清理安全请求 DTO
 */
@Data
@Schema(description = "确认机器安全请求参数")
public class ConfirmSafeRequest {

    @Schema(description = "打印机ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "打印机 ID 不能为空")
    @Positive(message = "打印机 ID 必须为正数")
    private Long printerId;

    @Schema(description = "已废弃，后端始终使用当前 JWT 用户 ID")
    private Long operatorId;
}
