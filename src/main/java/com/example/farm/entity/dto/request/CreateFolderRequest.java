package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新建文件夹请求 DTO
 */
@Data
@Schema(description = "新建文件夹请求参数")
public class CreateFolderRequest {

    @Schema(description = "父目录ID（NULL表示在根目录创建）")
    @Positive(message = "父目录 ID 必须为正数")
    private Long parentId;

    @Schema(description = "文件夹名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "文件夹名称不能为空")
    @Size(max = 100, message = "文件夹名称不能超过100个字符")
    @Pattern(regexp = "^[^/\\\\:*?\"<>|\\p{Cntrl}]+$", message = "文件夹名称包含非法字符")
    private String folderName;
}
