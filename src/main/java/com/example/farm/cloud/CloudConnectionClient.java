package com.example.farm.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 管理连接时使用独立网关凭据，不转发本地管理员 JWT。 */
@Component
public class CloudConnectionClient {
    private final ObjectMapper json = new ObjectMapper();

    public CloudWire.Credential bind(CloudGatewayProperties properties, String ticket) throws Exception {
        var credential = post(properties, "/api/v1/gateways/bind", null,
                Map.of("bindingToken", ticket), CloudWire.Credential.class);
        CloudGatewayAgent.validate(credential);
        return credential;
    }

    public CloudConnectionSettings.BindingInfo profile(CloudGatewayProperties properties,
                                                       CloudWire.Credential credential) throws Exception {
        return post(properties, "/api/v1/gateway/connection", credential.credential(),
                identity(credential), CloudConnectionSettings.BindingInfo.class);
    }

    public void disconnect(CloudGatewayProperties properties, CloudWire.Credential credential) throws Exception {
        try {
            var result = post(properties, "/api/v1/gateway/disconnect", credential.credential(),
                    identity(credential), Operation.class);
            if (!result.success()) { throw new IOException("CLOUD_DISCONNECT_REJECTED"); }
        } catch (RemoteException exception) {
            // A lost successful response can be retried after the credential was already invalidated.
            if (exception.status != 401) { throw exception; }
        }
    }

    static void validateConfiguration(CloudGatewayProperties properties) throws Exception {
        CloudTls.apiUri(properties.getApiUrl());
        URI broker = URI.create(properties.getMqttUrl());
        if (!"ssl".equals(broker.getScheme()) || broker.getHost() == null || broker.getUserInfo() != null
                || broker.getQuery() != null || broker.getFragment() != null || broker.getPort() == 0
                || broker.getPort() > 65535 || (broker.getPath() != null && !broker.getPath().isEmpty())) {
            throw new IllegalArgumentException("MQTT_TLS_REQUIRED");
        }
        if (properties.getCaCertificatePem().isBlank()) { CloudTls.trusted(properties.getCaCertificate()); }
        else { CloudTls.trustedPem(properties.getCaCertificatePem()); }
    }

    private Map<String, Object> identity(CloudWire.Credential credential) {
        return Map.of("gatewayId", credential.gatewayId(), "bindingVersion", credential.bindingVersion());
    }

    private <T> T post(CloudGatewayProperties properties, String path, String token,
                       Object body, Class<T> resultType) throws Exception {
        var tls = properties.getCaCertificatePem().isBlank() ? CloudTls.trusted(properties.getCaCertificate())
                : CloudTls.trustedPem(properties.getCaCertificatePem());
        try (var client = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create(CloudTls.apiUri(properties.getApiUrl()) + path))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
            if (token != null) { request.header("Authorization", "Bearer " + token); }
            var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                byte[] bytes = input.readNBytes(8193);
                if (bytes.length > 8192) { throw new IOException("CLOUD_RESPONSE_TOO_LARGE"); }
                if (response.statusCode() != 200) { throw new RemoteException(response.statusCode()); }
                return json.readValue(bytes, resultType);
            }
        }
    }

    private record Operation(boolean success) { }
    static class RemoteException extends IOException {
        private final int status;
        RemoteException(int status) { super("CLOUD_HTTP_" + status); this.status = status; }
    }
}
