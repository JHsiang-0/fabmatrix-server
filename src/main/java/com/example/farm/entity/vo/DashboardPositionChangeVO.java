package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 布局保存结果中的设备坐标摘要。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DashboardPositionChangeVO", description = "设备最终坐标摘要")
public class DashboardPositionChangeVO {
    @Schema(description = "打印机 ID", example = "311")
    private Long printerId;
    @Schema(description = "最终物理行；null 表示未分配")
    private Integer gridRow;
    @Schema(description = "最终物理列；null 表示未分配")
    private Integer gridCol;
}
