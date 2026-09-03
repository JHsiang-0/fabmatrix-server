package com.example.farm.entity.enums;

/** 批量计划逐项生命周期。 */
public enum DispatchPlanItemStatus {
    PENDING,
    ASSIGNED,
    UPLOADING,
    READY,
    SUCCEEDED,
    FAILED,
    RETRYABLE
}
