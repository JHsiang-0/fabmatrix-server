package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 动态看板布局原子保存成功结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DashboardLayoutSaveResultVO", description = "动态看板布局保存结果")
public class DashboardLayoutSaveResultVO {
    @Schema(description = "保存后的完整布局")
    private DashboardLayoutVO layout;
    @Schema(description = "本次显式及自动处理的最终坐标")
    private List<DashboardPositionChangeVO> changedPositions = new ArrayList<>();
    @Schema(description = "因 UNALLOCATE 策略自动下架的设备")
    private List<DashboardPrinterPositionVO> unallocatedPrinters = new ArrayList<>();
}
