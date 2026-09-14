package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Local Edition 本机初始化入口返回的短期 bootstrap 凭据。 */
@Data
@AllArgsConstructor
@Schema(description = "Local Edition 本机初始化凭据")
public class BootstrapTokenDTO {

    @Schema(description = "一次性初始化凭据；仅供本机 setup/admin 请求使用")
    private String token;

    @Schema(description = "凭据过期时间（Unix 毫秒）")
    private long expiresAt;
}
