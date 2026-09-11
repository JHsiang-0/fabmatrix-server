package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 布局保存冲突和缩容问题的安全结构化详情。 */
@Data
@Schema(name = "DashboardLayoutProblemVO", description = "动态看板布局保存问题详情")
public class DashboardLayoutProblemVO {
    @Schema(description = "稳定问题原因", example = "OUT_OF_BOUNDS")
    private String reason;
    @Schema(description = "服务端当前版本；版本冲突时返回", nullable = true, example = "2")
    private Long serverVersion;
    @Schema(description = "冲突设备 ID 列表")
    private List<Long> conflicts = new ArrayList<>();
    @Schema(description = "缩容时越界设备完整清单")
    private List<DashboardPrinterPositionVO> outOfBoundsPrinters = new ArrayList<>();

    public DashboardLayoutProblemVO() {
    }

    public DashboardLayoutProblemVO(String reason, Long serverVersion,
                                    List<Long> conflicts,
                                    List<DashboardPrinterPositionVO> outOfBoundsPrinters) {
        this.reason = reason;
        this.serverVersion = serverVersion;
        if (conflicts != null) {
            this.conflicts = conflicts;
        }
        if (outOfBoundsPrinters != null) {
            this.outOfBoundsPrinters = outOfBoundsPrinters;
        }
    }
}
