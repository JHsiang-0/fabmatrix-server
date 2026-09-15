package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 首次管理员初始化状态。
 */
@Data
@AllArgsConstructor
@Schema(description = "首次管理员初始化状态")
public class FirstAdminSetupStatusDTO {

    @Schema(description = "数据库是否已有用户或首次管理员初始化是否已永久关闭")
    private boolean initialized;

    @Schema(description = "当前实例是否允许进行首次管理员初始化")
    private boolean setupAvailable;
}
