package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 打印机状态历史分页查询参数。
 */
@Data
@Schema(description = "打印机状态历史分页查询参数")
public class PrinterHistoryQueryDTO {

    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于1")
    @Schema(description = "页码，从1开始", example = "1", defaultValue = "1")
    private Integer pageNum = 1;

    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    @Schema(description = "每页数量，最大100", example = "20", defaultValue = "20")
    private Integer pageSize = 20;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "开始时间，ISO-8601 本地时间，包含该时间", example = "2026-09-01T00:00:00")
    private LocalDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "结束时间，ISO-8601 本地时间，包含该时间", example = "2026-09-02T23:59:59")
    private LocalDateTime to;
}
