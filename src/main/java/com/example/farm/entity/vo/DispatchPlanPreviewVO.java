package com.example.farm.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 批量分配预览响应。 */
@Data
public class DispatchPlanPreviewVO {
    private String planId;
    private Long version;
    private String mode;
    private String strategy;
    private String action;
    private String confirmationToken;
    private LocalDateTime expiresAt;
    private List<DispatchPlanItemVO> items = new ArrayList<>();
    private List<DispatchConflictVO> conflicts = new ArrayList<>();
}
