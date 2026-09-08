package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量移动文件请求 DTO。
 */
@Data
@Schema(description = "批量移动文件请求参数")
public class BatchMoveFilesRequest {

    @Schema(description = "文件 ID，单次最多100个", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "请选择要移动的文件")
    @Size(max = 100, message = "单次最多移动100个文件")
    private List<@Valid @NotNull(message = "文件 ID 不能为空") @Positive(message = "文件 ID 必须为正数") Long> ids;

    @Schema(description = "目标文件夹 ID；null 表示根目录")
    @Positive(message = "目标目录 ID 必须为正数")
    private Long parentId;
}
