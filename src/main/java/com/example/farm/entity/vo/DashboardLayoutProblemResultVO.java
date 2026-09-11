package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 动态看板布局问题的统一 Result envelope，仅用于 OpenAPI 精确描述。 */
@Data
@Schema(name = "ResultDashboardLayoutProblem", description = "动态看板布局问题统一响应")
public class DashboardLayoutProblemResultVO {

    @Schema(description = "业务状态码；布局问题按场景为 400、404、409 或 422", example = "409")
    private long code;

    @Schema(description = "面向用户的结果说明", example = "看板布局保存失败: SLOT_CONFLICT")
    private String message;

    @Schema(description = "结构化布局问题详情")
    private DashboardLayoutProblemVO data;

    @Schema(description = "Unix epoch 毫秒时间戳", example = "1760000000000")
    private long timestamp;
}
