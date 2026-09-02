package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录结果 DTO
 */
@Data
@Schema(description = "登录返回结果")
public class LoginResultDTO {

    @Schema(description = "JWT Token")
    private String token;

    @Schema(description = "Token 有效期（秒）")
    private Long expiresIn;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "角色")
    private String role;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    public LoginResultDTO(String token, Long expiresIn, Long userId, String username, String role) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.userId = userId;
        this.username = username;
        this.role = role;
    }
}
