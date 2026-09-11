package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 一次布局保存中的设备坐标变化。 */
@Data
@Schema(name = "DashboardPositionChangeDTO", description = "动态看板设备坐标变化")
public class DashboardPositionChangeDTO {

    @NotNull(message = "打印机 ID 不能为空")
    @Positive(message = "打印机 ID 必须为正数")
    @Schema(description = "打印机 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "311")
    private Long printerId;

    @Schema(description = "目标物理行；与 gridCol 必须同时为空或非空", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true, example = "1", maximum = "30")
    @Min(value = 1, message = "目标行必须为正数")
    @Max(value = 30, message = "目标行不能超过 30")
    private Integer gridRow;

    @Schema(description = "目标物理列；与 gridRow 必须同时为空或非空", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true, example = "2", maximum = "60")
    @Min(value = 1, message = "目标列必须为正数")
    @Max(value = 60, message = "目标列不能超过 60")
    private Integer gridCol;
}
