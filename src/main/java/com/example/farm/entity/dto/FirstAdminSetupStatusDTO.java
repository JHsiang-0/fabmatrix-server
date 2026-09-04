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

    @Schema(description = "系统是否已经存在用户")
    private boolean initialized;

    @Schema(description = "当前实例是否允许进行首次管理员初始化")
    private boolean setupAvailable;
}
