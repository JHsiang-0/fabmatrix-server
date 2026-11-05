package com.example.farm.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CloudPairingStartRequest(@Min(0) long revision,
        @NotBlank @Size(max=512) String apiUrl, @NotBlank @Size(max=512) String mqttUrl,
        @Size(max=131072) String caCertificatePem, boolean useSystemTrust) {
    @Override public String toString() { return "CloudPairingStartRequest[redacted]"; }
}
