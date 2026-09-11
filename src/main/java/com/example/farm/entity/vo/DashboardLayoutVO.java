package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 动态看板布局的统一 REST 响应对象。 */
@Data
@Schema(name = "DashboardLayoutVO", description = "动态看板布局")
public class DashboardLayoutVO {

    @Schema(description = "物理行数", example = "4")
    private Integer rowCount;

    @Schema(description = "物理列数", example = "12")
    private Integer columnCount;

    @Schema(description = "视觉过道列表")
    private List<DashboardAisleVO> aisles;

    @Schema(description = "布局乐观锁版本", example = "1")
    private Long version;

    @Schema(description = "布局约束；前端应以此为准")
    private DashboardLayoutLimitsVO limits;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;
}
