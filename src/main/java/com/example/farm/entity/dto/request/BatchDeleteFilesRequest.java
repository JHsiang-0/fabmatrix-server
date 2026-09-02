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
 * 批量删除文件请求 DTO。
 */
@Data
@Schema(description = "批量删除文件请求参数")
public class BatchDeleteFilesRequest {

    @Schema(description = "文件或文件夹 ID，单次最多100个", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "请选择要删除的文件")
    @Size(max = 100, message = "单次最多删除100个文件")
    private List<@Valid @NotNull(message = "文件 ID 不能为空") @Positive(message = "文件 ID 必须为正数") Long> ids;
}
