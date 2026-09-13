package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求 DTO
 */
@Data
@Schema(description = "修改密码请求参数")
public class ChangePasswordDTO {

    @NotBlank(message = "旧密码不能为空")
    @Schema(description = "旧密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 7, max = 20, message = "密码长度必须在 7-20 个字符之间")
    @Schema(description = "新密码（7-20位，仅限制长度）", requiredMode = Schema.RequiredMode.REQUIRED, example = "NewPass123")
    private String newPassword;

    @NotBlank(message = "确认密码不能为空")
    @Schema(description = "确认密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "NewPass123")
    private String confirmPassword;

    /**
     * 校验新密码和确认密码是否一致
     */
    public boolean isNewPasswordMatch() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }
}
