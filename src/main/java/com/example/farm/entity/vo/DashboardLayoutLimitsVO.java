package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 前端表单和布局校验共同使用的服务端限制。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DashboardLayoutLimitsVO", description = "动态看板布局限制")
public class DashboardLayoutLimitsVO {

    @Schema(description = "最小物理行数", example = "1")
    private int minRows;
    @Schema(description = "最大物理行数", example = "30")
    private int maxRows;
    @Schema(description = "最小物理列数", example = "1")
    private int minColumns;
    @Schema(description = "最大物理列数", example = "60")
    private int maxColumns;
    @Schema(description = "最大物理槽位数", example = "1800")
    private int maxCells;
    @Schema(description = "最大过道数", example = "20")
    private int maxAisles;
    @Schema(description = "过道最小尺寸，单位 CSS px", example = "48")
    private int minAisleSize;
    @Schema(description = "过道最大尺寸，单位 CSS px", example = "240")
    private int maxAisleSize;

    public static DashboardLayoutLimitsVO defaults() {
        return new DashboardLayoutLimitsVO(1, 30, 1, 60, 1800, 20, 48, 240);
    }
}
