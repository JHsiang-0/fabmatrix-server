package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改打印任务优先级请求。
 */
@Data
@Schema(description = "修改打印任务优先级请求")
public class UpdatePrintJobPriorityRequest {

    @NotNull(message = "任务优先级不能为空")
    @Min(value = 0, message = "任务优先级不能小于0")
    @Max(value = 100, message = "任务优先级不能超过100")
    @Schema(description = "任务优先级，数字越大越优先", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer priority;
}
