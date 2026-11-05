package com.example.farm.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 管理员提交的一次性绑定材料，不参与日志输出。 */
public record CloudBindRequest(@Min(0) long revision,
        @NotBlank @Size(max = 512) String apiUrl,
        @NotBlank @Size(max = 512) String mqttUrl,
        @NotBlank @Pattern(regexp = "fmc_b_[A-Za-z0-9_-]{43}") String bindingToken,
        @Size(max = 131072) String caCertificatePem, boolean useSystemTrust) {
    @Override public String toString() { return "CloudBindRequest[redacted]"; }
}
