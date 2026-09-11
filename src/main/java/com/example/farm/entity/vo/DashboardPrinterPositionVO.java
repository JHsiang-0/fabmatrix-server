package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 结构化布局问题中的设备位置。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DashboardPrinterPositionVO", description = "打印机及其原有坐标")
public class DashboardPrinterPositionVO {
    @Schema(description = "打印机 ID", example = "403")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long printerId;
    @Schema(description = "打印机名称", example = "A-03")
    private String name;
    @Schema(description = "原物理行")
    private Integer gridRow;
    @Schema(description = "原物理列")
    private Integer gridCol;
}
