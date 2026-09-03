package com.example.farm.entity.enums;

/** 批量计划生命周期。 */
public enum DispatchPlanStatus {
    PREVIEWED,
    CONFIRMED,
    EXECUTING,
    COMPLETED,
    PARTIAL_FAILED,
    EXPIRED,
    CANCELLED
}
