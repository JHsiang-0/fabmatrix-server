package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 新建文件夹请求 DTO
 */
@Data
@Schema(description = "新建文件夹请求参数")
public class CreateFolderRequest {

    @Schema(description = "父目录ID（NULL表示在根目录创建）")
    private Long parentId;

    @Schema(description = "文件夹名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String folderName;
}