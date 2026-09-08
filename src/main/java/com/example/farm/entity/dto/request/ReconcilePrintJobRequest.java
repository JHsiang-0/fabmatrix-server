package com.example.farm.entity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 人工核对断网/重启后任务结果。 */
@Data
public class ReconcilePrintJobRequest {

    @NotBlank(message = "核对结果不能为空")
    @Pattern(regexp = "COMPLETED|FAILED|CANCELLED|QUEUED",
            message = "核对结果必须是 COMPLETED、FAILED、CANCELLED 或 QUEUED")
    private String result;

    @Size(max = 255, message = "核对说明最多255个字符")
    private String reason;
}
