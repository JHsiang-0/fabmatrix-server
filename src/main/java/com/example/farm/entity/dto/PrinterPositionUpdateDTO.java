package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 打印机位置更新 DTO（用于数字孪生看板拖拽）
 */
@Data
@Schema(description = "打印机位置更新参数")
public class PrinterPositionUpdateDTO {

    @Schema(description = "设备ID (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "物理位置 - 网格行号 (1-4，null 表示移回待分配区)")
    private Integer gridRow;

    @Schema(description = "物理位置 - 网格列号 (1-12，null 表示移回待分配区)")
    private Integer gridCol;
}