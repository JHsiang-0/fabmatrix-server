package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 密码存储状态统计 DTO
 */
@Data
@AllArgsConstructor
@Schema(description = "密码存储状态统计")
public class PasswordStatusResultDTO {

    @Schema(description = "加密用户数量")
    private int encryptedCount;

    @Schema(description = "明文用户数量")
    private int plainCount;

    @Schema(description = "总用户数量")
    private int totalCount;
}
