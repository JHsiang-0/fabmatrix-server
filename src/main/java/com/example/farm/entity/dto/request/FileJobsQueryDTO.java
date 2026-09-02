package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 文件关联任务分页参数。
 */
@Data
@Schema(description = "文件关联任务分页参数")
public class FileJobsQueryDTO {

    @Schema(description = "页码", example = "1", defaultValue = "1")
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNum = 1;

    @Schema(description = "每页数量", example = "10", defaultValue = "10")
    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer pageSize = 10;
}
