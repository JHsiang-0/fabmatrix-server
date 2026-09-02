package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 打印机任务统计查询参数。
 */
@Data
@Schema(description = "打印机任务统计查询参数")
public class PrinterStatisticsQueryDTO {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "统计开始时间，按任务创建时间筛选", example = "2026-09-01T00:00:00")
    private LocalDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "统计结束时间，按任务创建时间筛选", example = "2026-09-02T23:59:59")
    private LocalDateTime to;
}
