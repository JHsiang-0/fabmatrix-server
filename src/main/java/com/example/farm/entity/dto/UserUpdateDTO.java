package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 用户信息更新 DTO
 */
@Data
@Schema(description = "用户信息更新参数")
public class UserUpdateDTO {

    @Schema(description = "用户ID (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱", example = "user@example.com")
    private String email;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "手机号", example = "13800138000")
    private String phone;

    @Schema(description = "角色：ADMIN(管理员), OPERATOR(操作员)")
    private String role;
}
