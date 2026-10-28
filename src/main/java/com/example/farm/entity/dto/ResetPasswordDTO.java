package com.example.farm.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 不生成 toString，避免临时密码进入日志。 */
@Getter
@Setter
public class ResetPasswordDTO {
    @NotBlank @Size(min = 6, max = 20)
    private String password;
    @NotBlank @Size(min = 6, max = 20)
    private String confirmPassword;
}
