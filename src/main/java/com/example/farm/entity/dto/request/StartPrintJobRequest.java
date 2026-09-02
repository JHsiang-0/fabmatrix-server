package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 现场启动打印任务请求 DTO
 */
@Data
@Schema(description = "启动打印任务请求参数")
public class StartPrintJobRequest {

    @Schema(description = "任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务 ID 不能为空")
    @Positive(message = "任务 ID 必须为正数")
    private Long jobId;

    @Schema(description = "已废弃，后端始终使用当前 JWT 用户 ID")
    private Long operatorId;

    /**
     * 执行动作：
     * - START_PRINT: 下发文件并立即开始打印
     * - UPLOAD_ONLY: 仅上传文件到机器，不自动打印
     */
    @Schema(description = "执行动作: START_PRINT(下发并打印) 或 UPLOAD_ONLY(仅上传)", example = "START_PRINT")
    @Pattern(regexp = "(?i)^(START_PRINT|UPLOAD_ONLY)$", message = "action 只能是 START_PRINT 或 UPLOAD_ONLY")
    private String action;

    /**
     * 获取实际执行的 action，默认 START_PRINT
     */
    public String getAction() {
        return action != null && !action.isEmpty() ? action : "START_PRINT";
    }
}
