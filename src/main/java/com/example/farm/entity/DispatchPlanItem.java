package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 批量分配计划的逐项匹配和执行结果。 */
@Data
@TableName("farm_dispatch_plan_item")
public class DispatchPlanItem {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String planId;
    private Long fileId;
    private Long printerId;
    private Long jobId;
    private String status;
    private String reasonCode;
    private String message;
    private Integer attemptCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
