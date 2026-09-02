package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 创建打印任务请求 DTO
 */
@Data
@Schema(description = "创建打印任务请求参数")
public class PrintJobCreateDTO {

    @Schema(description = "切片文件ID (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long fileId;

    @Schema(description = "任务优先级 (数字越大越优先，默认 0)")
    private Integer priority;
}
