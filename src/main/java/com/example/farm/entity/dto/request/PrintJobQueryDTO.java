package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打印任务查询参数 DTO
 */
@Data
@Schema(description = "打印任务查询参数")
public class PrintJobQueryDTO {

    @Schema(description = "页码，默认1", example = "1")
    private Integer pageNum = 1;

    @Schema(description = "每页数量，默认10", example = "10")
    private Integer pageSize = 10;

    @Schema(description = "任务状态：PENDING, QUEUED, ASSIGNED, PRINTING, PAUSED, READY, COMPLETED, FAILED, CANCELLED")
    private String status;

    @Schema(description = "打印机ID")
    private Long printerId;

    @Schema(description = "用户ID（发起任务的用户）")
    private Long userId;

    @Schema(description = "创建时间起")
    private LocalDateTime startTime;

    @Schema(description = "创建时间止")
    private LocalDateTime endTime;
}