package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/** 管理员操作日志分页筛选参数。 */
@Data
@Schema(description = "操作日志分页查询参数")
public class AuditLogQueryDTO {

    @Min(value = 1, message = "页码必须大于等于1")
    @Schema(description = "页码", defaultValue = "1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    @Schema(description = "每页数量", defaultValue = "20")
    private Integer pageSize = 20;

    @Positive(message = "操作者 ID 必须为正数")
    private Long actorId;

    @Size(max = 64, message = "动作筛选长度不能超过64")
    private String action;

    @Size(max = 32, message = "目标类型筛选长度不能超过32")
    private String targetType;

    @Size(max = 64, message = "目标 ID 筛选长度不能超过64")
    private String targetId;

    @Pattern(regexp = "SUCCESS|FAILURE", message = "结果只能为 SUCCESS 或 FAILURE")
    private String result;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime to;
}
