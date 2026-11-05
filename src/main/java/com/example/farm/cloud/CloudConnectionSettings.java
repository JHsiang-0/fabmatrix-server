package com.example.farm.cloud;

/** 仅写入受保护的 Server 配置文件，不作为 API 响应返回。 */
record CloudConnectionSettings(long revision, boolean enabled, String apiUrl, String mqttUrl,
        String caCertificate, String caCertificatePem, boolean observationV2, boolean observationV3,
        CloudWire.Credential credential, BindingInfo binding, Long lastAckAt) {
    record BindingInfo(String gatewayId, String gatewayName, String organizationId,
                       String organizationName, String farmId, String farmName) { }
    @Override public String toString() { return "CloudConnectionSettings[redacted]"; }
}
