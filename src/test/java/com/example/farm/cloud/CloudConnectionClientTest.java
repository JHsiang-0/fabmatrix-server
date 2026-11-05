package com.example.farm.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudConnectionClientTest {
    @Test void pairingWireKeepsConfirmationAndDeviceCapabilitiesSeparate() throws Exception {
        var json = new ObjectMapper();
        var session = new CloudConnectionClient.PairingStart(UUID.randomUUID().toString(), "fmc_pd_" + "d".repeat(43),
                "fmc_pa_" + "a".repeat(43), System.currentTimeMillis() + 600000);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/api/v1/gateway-pairings/", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            String path = exchange.getRequestURI().getPath();
            String response;
            if (path.endsWith("/start")) { response = json.writeValueAsString(session); }
            else {
                var body = json.readTree(exchange.getRequestBody());
                assertThat(body.get("pairingId").asText()).isEqualTo(session.pairingId());
                assertThat(body.get("deviceToken").asText()).isEqualTo(session.deviceToken());
                assertThat(body.has("approvalToken")).isFalse();
                assertThat(body.get("credential").asText()).isEqualTo("fmc_g_" + "g".repeat(43));
                response = path.endsWith("/poll")
                        ? json.writeValueAsString(new CloudConnectionClient.PairingResult("WAITING", session.expiresAt(), null))
                        : "{\"success\":true}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var properties = new CloudGatewayProperties();
            properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new CloudConnectionClient();
            assertThat(client.startPairing(properties)).isEqualTo(session);
            assertThat(client.pollPairing(properties, session, "fmc_g_" + "g".repeat(43)).state()).isEqualTo("WAITING");
            client.cancelPairing(properties, session, "fmc_g_" + "g".repeat(43));
            assertThat(requests.get()).isEqualTo(3);
        } finally { server.stop(0); }
    }

    @Test void localServerExchangesOnlyBindingTicketAndGatewayCredential() throws Exception {
        String gateway = UUID.randomUUID().toString();
        var credential = new CloudWire.Credential(gateway, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, "fmc_g_" + "g".repeat(43), System.currentTimeMillis() + 86400000,
                gateway + ".v1", gateway, "fabmatrix/v1/gateways/" + gateway + "/bindings/1/");
        var json = new ObjectMapper();
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            requests.incrementAndGet();
            var body = json.readTree(exchange.getRequestBody());
            String response;
            if (exchange.getRequestURI().getPath().endsWith("/bind")) {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                assertThat(body.get("bindingToken").asText()).isEqualTo("fmc_b_" + "b".repeat(43));
                response = json.writeValueAsString(credential);
            } else {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + credential.credential());
                assertThat(body.get("gatewayId").asText()).isEqualTo(gateway);
                response = exchange.getRequestURI().getPath().endsWith("/connection")
                        ? json.writeValueAsString(new CloudConnectionSettings.BindingInfo(gateway, "网关", credential.organizationId(),
                        "组织", credential.farmId(), "农场")) : "{\"success\":true}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var properties = new CloudGatewayProperties();
            properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new CloudConnectionClient();
            var bound = client.bind(properties, "fmc_b_" + "b".repeat(43));
            assertThat(client.profile(properties, bound).farmName()).isEqualTo("农场");
            client.disconnect(properties, bound);
            assertThat(requests.get()).isEqualTo(3);
        } finally { server.stop(0); }
    }

    @Test void invalidCertificateAndInsecureRemoteUrlsAreRejected() {
        assertThatThrownBy(() -> CloudTls.trustedPem("-----BEGIN PRIVATE KEY-----\nsynthetic"))
                .isInstanceOf(IllegalArgumentException.class);
        var properties = new CloudGatewayProperties();
        properties.setApiUrl("https://cloud.example.test");
        properties.setMqttUrl("ssl://mqtt.example.test:8883/#fragment");
        assertThatThrownBy(() -> CloudConnectionClient.validateConfiguration(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
