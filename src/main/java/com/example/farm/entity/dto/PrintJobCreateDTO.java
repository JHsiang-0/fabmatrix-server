package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建打印任务请求 DTO
 */
@Data
@Schema(description = "创建打印任务请求参数")
public class PrintJobCreateDTO {

    @Schema(description = "切片文件ID (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "切片文件 ID 不能为空")
    @Positive(message = "切片文件 ID 必须为正数")
    private Long fileId;

    @Schema(description = "任务优先级 (数字越大越优先，默认 0)")
    @Max(value = 100, message = "任务优先级不能超过100")
    @jakarta.validation.constraints.Min(value = 0, message = "任务优先级不能小于0")
    private Integer priority;

    @Schema(description = "可选目标打印机 ID；传入后只派发不直接启动")
    @Positive(message = "打印机 ID 必须为正数")
    private Long printerId;

    @Schema(description = "客户端幂等键；同一用户重复提交时返回首次任务")
    @Size(max = 100, message = "幂等键长度不能超过100")
    private String idempotencyKey;
}
