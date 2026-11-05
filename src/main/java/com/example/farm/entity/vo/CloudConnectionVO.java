package com.example.farm.entity.vo;

/** 本地管理员视图，排除绑定码、网关凭据、证书内容及服务器文件路径。 */
public record CloudConnectionVO(long revision, boolean enabled, boolean bound, String state,
        boolean connected, String apiUrl, String mqttUrl, boolean caCertificateConfigured,
        String gatewayId, String gatewayName, String organizationId, String organizationName,
        String farmId, String farmName, Long credentialExpiresAt, Long lastAckAt,
        String errorCode, boolean pending, long printerCount, boolean fullModelEnabled) {
}
