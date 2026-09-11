package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 打印机位置更新 DTO（用于数字孪生看板拖拽）
 */
@Data
@Schema(description = "已弃用 positions 兼容接口的位置更新项；非空坐标必须位于当前动态布局内，布局上限为 30×60，物理槽位总数不超过 1800")
public class PrinterPositionUpdateDTO {

    @Schema(description = "设备ID (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "设备 ID 不能为空")
    @Positive(message = "设备 ID 必须为正数")
    private Long id;

    @Schema(description = "物理位置行号；与 gridCol 同时为 null 表示移回待分配区，非空时必须位于当前布局内（布局行数上限 30）")
    private Integer gridRow;

    @Schema(description = "物理位置列号；与 gridRow 同时为 null 表示移回待分配区，非空时必须位于当前布局内（布局列数上限 60）")
    private Integer gridCol;
}
