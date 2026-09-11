package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 缩容时对越界设备的处理策略。 */
@Schema(description = "缩容越界策略：拒绝保存或原子下架")
public enum DashboardOverflowPolicy {
    REJECT,
    UNALLOCATE
}
