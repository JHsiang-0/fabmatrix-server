package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 人工核对断网/重启后任务结果。 */
@Data
@Schema(name = "ReconcilePrintJobRequest", description = "RECONCILING 任务人工核对请求；目标状态为 COMPLETED、FAILED、CANCELLED 或 QUEUED")
public class ReconcilePrintJobRequest {

    @Schema(description = "核对目标状态", allowableValues = {"COMPLETED", "FAILED", "CANCELLED", "QUEUED"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "核对结果不能为空")
    @Pattern(regexp = "COMPLETED|FAILED|CANCELLED|QUEUED",
            message = "核对结果必须是 COMPLETED、FAILED、CANCELLED 或 QUEUED")
    private String result;

    @Schema(description = "核对说明；最多255个字符")
    @Size(max = 255, message = "核对说明最多255个字符")
    private String reason;
}
