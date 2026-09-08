package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 移动文件夹请求 DTO。 */
@Data
@Schema(description = "移动文件夹请求参数")
public class MoveFolderRequest {

    @Schema(description = "目标一级目录 ID；NULL 表示根目录")
    @Positive(message = "目标目录 ID 必须为正数")
    private Long parentId;
}
