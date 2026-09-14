package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户注册请求 DTO
 */
@Data
@Schema(description = "用户注册请求参数")
public class UserRegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度必须在 3-20 个字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    @Schema(description = "用户名（3-20位，只能包含字母、数字和下划线）", requiredMode = Schema.RequiredMode.REQUIRED, example = "admin")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须在 6-20 个字符之间")
    @Schema(description = "密码（6-20位，仅限制长度）", requiredMode = Schema.RequiredMode.REQUIRED, example = "Admin123")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    @Schema(description = "确认密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "Admin123")
    private String confirmPassword;

    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱", example = "admin@example.com")
    private String email;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "手机号", example = "13800138000")
    private String phone;

    /**
     * Local Edition 首次初始化凭据。普通管理员创建操作员时不使用该字段，
     * 也不会在任何用户 VO 中返回。
     */
    @Schema(description = "Local Edition 首次初始化一次性凭据，仅 setup/admin 使用",
            accessMode = Schema.AccessMode.WRITE_ONLY, example = "bootstrap-token")
    private String bootstrapToken;

    /**
     * 校验密码和确认密码是否一致
     */
    public boolean isPasswordMatch() {
        return password != null && password.equals(confirmPassword);
    }
}
