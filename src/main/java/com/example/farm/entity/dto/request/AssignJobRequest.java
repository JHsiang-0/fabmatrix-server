package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 分配任务请求 DTO（后台派发）
 */
@Data
@Schema(description = "分配任务请求参数")
public class AssignJobRequest {

    @Schema(description = "任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务 ID 不能为空")
    @Positive(message = "任务 ID 必须为正数")
    private Long jobId;

    @Schema(description = "目标打印机ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "打印机 ID 不能为空")
    @Positive(message = "打印机 ID 必须为正数")
    private Long printerId;
}
