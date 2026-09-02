package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打印机任务统计安全响应对象。
 */
@Data
@Schema(name = "PrinterStatisticsVO", description = "打印机任务统计")
public class PrinterStatisticsVO {

    private Long printerId;
    @Schema(description = "统计开始时间；null 表示不限制")
    private LocalDateTime from;
    @Schema(description = "统计结束时间；null 表示不限制")
    private LocalDateTime to;
    @Schema(description = "统计范围内任务总数")
    private Long totalJobs;
    private Long completedJobs;
    private Long failedJobs;
    private Long cancelledJobs;
    @Schema(description = "当前未结束任务数：ASSIGNED、READY、PRINTING、PAUSED")
    private Long activeJobs;
    @Schema(description = "成功率，已完成任务 / 已完成和失败任务，百分比0-100")
    private BigDecimal successRate;
    @Schema(description = "已结束任务的打印时长总和，单位秒")
    private Long totalPrintSeconds;
    @Schema(description = "已结束任务的平均打印时长，单位秒")
    private BigDecimal averagePrintSeconds;
}
