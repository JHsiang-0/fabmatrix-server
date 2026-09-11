package com.example.farm.entity.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/** 任务分页时间范围使用的时间字段。 */
@Schema(description = "任务查询时间字段；默认 CREATED_AT；COMPLETED_AT 不隐式限制任务状态")
public enum PrintJobTimeField {
    CREATED_AT,
    COMPLETED_AT
}
