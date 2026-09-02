package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分配任务请求 DTO（后台派发）
 */
@Data
@Schema(description = "分配任务请求参数")
public class AssignJobRequest {

    @Schema(description = "任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long jobId;

    @Schema(description = "目标打印机ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long printerId;
}