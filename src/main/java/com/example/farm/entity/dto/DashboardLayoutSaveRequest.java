package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 动态看板布局及设备坐标的原子保存请求。 */
@Data
@Schema(name = "DashboardLayoutSaveRequest", description = "动态看板布局原子保存请求")
public class DashboardLayoutSaveRequest {

    @NotNull(message = "expectedVersion 不能为空")
    @Positive(message = "expectedVersion 必须为正数")
    @Schema(description = "客户端读取到的布局版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long expectedVersion;

    @NotNull(message = "行数不能为空")
    @Min(value = 1, message = "行数必须在 1-30 之间")
    @Max(value = 30, message = "行数必须在 1-30 之间")
    @Schema(description = "物理行数", requiredMode = Schema.RequiredMode.REQUIRED, example = "6")
    private Integer rowCount;

    @NotNull(message = "列数不能为空")
    @Min(value = 1, message = "列数必须在 1-60 之间")
    @Max(value = 60, message = "列数必须在 1-60 之间")
    @Schema(description = "物理列数", requiredMode = Schema.RequiredMode.REQUIRED, example = "16")
    private Integer columnCount;

    @NotNull(message = "过道列表不能为空")
    @Size(max = 20, message = "过道数量不能超过 20 条")
    @Valid
    @Schema(description = "过道列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<DashboardAisleDTO> aisles;

    @NotNull(message = "缩容策略不能为空")
    @Schema(description = "缩容策略：REJECT 或 UNALLOCATE", requiredMode = Schema.RequiredMode.REQUIRED)
    private DashboardOverflowPolicy overflowPolicy;

    @NotNull(message = "位置变化列表不能为空")
    @Size(max = 100, message = "单次最多更新 100 台设备")
    @Valid
    @Schema(description = "显式设备坐标变化列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<DashboardPositionChangeDTO> positionChanges;
}
