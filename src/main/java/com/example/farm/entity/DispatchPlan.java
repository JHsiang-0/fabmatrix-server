package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 持久化的用户批量分配计划。 */
@Data
@TableName("farm_dispatch_plan")
public class DispatchPlan {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String mode;
    private String strategy;
    private String action;
    private String status;
    private Long version;
    private String confirmationTokenHash;
    private Long createdBy;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
