package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 批量分配预览响应。 */
@Data
@Schema(name = "DispatchPlanPreviewVO", description = "批量分配预览结果，不产生设备副作用")
public class DispatchPlanPreviewVO {
    @Schema(description = "服务端生成的计划 ID")
    private String planId;
    @Schema(description = "计划版本")
    private Long version;
    @Schema(description = "计划模式")
    private String mode;
    @Schema(description = "匹配策略")
    private String strategy;
    @Schema(description = "执行动作")
    private String action;
    @Schema(description = "确认令牌，过期后不可使用")
    private String confirmationToken;
    @Schema(description = "预览过期时间")
    private LocalDateTime expiresAt;
    @Schema(description = "计划明细")
    private List<DispatchPlanItemVO> items = new ArrayList<>();
    @Schema(description = "无法执行的冲突明细")
    private List<DispatchConflictVO> conflicts = new ArrayList<>();
}
