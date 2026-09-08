package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 重命名文件夹请求 DTO。 */
@Data
@Schema(description = "重命名文件夹请求参数")
public class RenameFolderRequest {

    @Schema(description = "新的文件夹名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "文件夹名称不能为空")
    @Size(max = 100, message = "文件夹名称不能超过100个字符")
    @Pattern(regexp = "^[^/\\\\:*?\"<>|\\p{Cntrl}]+$", message = "文件夹名称包含非法字符")
    private String folderName;
}
