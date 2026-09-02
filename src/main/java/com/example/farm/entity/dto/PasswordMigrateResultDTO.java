package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 密码迁移结果 DTO
 */
@Data
@AllArgsConstructor
@Schema(description = "密码迁移结果")
public class PasswordMigrateResultDTO {

    @Schema(description = "已迁移用户数量")
    private int migratedCount;

    @Schema(description = "已加密跳过数量")
    private int skippedCount;

    @Schema(description = "总用户数量")
    private int totalCount;

    public int getTotalCount() {
        return migratedCount + skippedCount;
    }
}
