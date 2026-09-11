package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打印任务查询参数 DTO
 */
@Data
@Schema(description = "当前实现的打印任务查询参数。TC-001 目标新增 statuses、fileName、timeField；status 与 statuses 互斥，目标时间按农场时区解释，默认 Asia/Shanghai")
public class PrintJobQueryDTO {

    @Schema(description = "页码，默认1", example = "1")
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNum = 1;

    @Schema(description = "每页数量，默认10", example = "10")
    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer pageSize = 10;

    @Schema(description = "兼容单值任务状态：QUEUED、ASSIGNED、UPLOADING、READY、PRINTING、PAUSED、RECONCILING、COMPLETED、FAILED、CANCELLED；遗留 PENDING/MANUAL/CANCELED 不由 GET 写库规范化")
    private String status;

    @Schema(description = "打印机ID")
    private Long printerId;

    @Schema(description = "用户ID（发起任务的用户）")
    private Long userId;

    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss", description = "时间范围起点；ISO-8601 local datetime，按农场时区解释，默认 Asia/Shanghai；当前实现按 createdAt")
    private LocalDateTime startTime;

    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss", description = "时间范围终点；ISO-8601 local datetime，按农场时区解释，默认 Asia/Shanghai；当前实现按 createdAt")
    private LocalDateTime endTime;
}
