package com.example.farm.cloud;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "farm.cloud")
public class CloudGatewayProperties {
    private boolean enabled;
    private boolean observationV2Enabled;
    private boolean observationV3Enabled;
    private String apiUrl = "";
    private String mqttUrl = "";
    private String caCertificate = "";
    private String credentialFile = System.getProperty("user.home") + "/FarmData/config/cloud-gateway.json";
    private String bindingTokenFile = "";
    private String stateDirectory = System.getProperty("user.home") + "/FarmData/cloud-agent";
}
