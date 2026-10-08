package com.example.farm.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 局域网打印机协议探测器。
 *
 * <p>7125 的 Moonraker 响应识别为 Klipper；RRF 通过 rr_connect 的 JSON
 * 错误/成功响应识别，即使设备配置了密码也能被识别为 RRF。</p>
 */
@Component
public class PrinterProtocolDetector {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PrinterProtocolDetector() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(800));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    PrinterProtocolDetector(RestClient restClient) {
        this.restClient = restClient;
    }

    public PrinterProtocolType detect(String ipAddress) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        if (probeKlipper(ipAddress)) {
            return PrinterProtocolType.KLIPPER;
        }
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        if (probeRrf(ipAddress)) {
            return PrinterProtocolType.RRF;
        }
        return null;
    }

    private boolean probeKlipper(String ipAddress) {
        try {
            String body = restClient.get()
                    .uri("http://" + ipAddress + ":7125/server/info")
                    .retrieve()
                    .body(String.class);
            JsonNode root = parse(body);
            return root != null && root.has("result");
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            return (status == 401 || status == 403) && !Thread.currentThread().isInterrupted()
                    && probeKlipperAuthorizationInfo(ipAddress);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Moonraker 官方允许未授权访问 /access/info；不可把任意 401 或任意 JSON 当作协议证据。 */
    private boolean probeKlipperAuthorizationInfo(String ipAddress) {
        try {
            String body = restClient.get()
                    .uri("http://" + ipAddress + ":7125/access/info")
                    .retrieve()
                    .body(String.class);
            JsonNode root = parse(body);
            JsonNode info = root == null ? null : root.get("result");
            if (info == null || !info.isObject() || !info.path("default_source").isTextual()
                    || !info.path("available_sources").isArray() || !info.path("login_required").isBoolean()
                    || !info.path("trusted").isBoolean()) {
                return false;
            }
            String source = info.path("default_source").asText();
            if (!"moonraker".equals(source) && !"ldap".equals(source)) {
                return false;
            }
            boolean supportedSource = false;
            for (JsonNode available : info.path("available_sources")) {
                if (!available.isTextual()) {
                    return false;
                }
                supportedSource |= source.equals(available.asText());
            }
            return supportedSource;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean probeRrf(String ipAddress) {
        boolean connected = false;
        String sessionKey = null;
        try {
            String body = restClient.get()
                    .uri("http://" + ipAddress + "/rr_connect?password=&sessionKey=yes")
                    .retrieve()
                    .body(String.class);
            JsonNode root = parse(body);
            JsonNode error = root == null ? null : root.get("err");
            if (error == null || !error.isIntegralNumber() || !error.canConvertToInt()
                    || error.asInt() < 0 || error.asInt() > 2) {
                return false;
            }
            connected = error.asInt() == 0;
            JsonNode key = root.get("sessionKey");
            sessionKey = key == null || key.isNull() ? null : key.asText();
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            // 仅释放属于本次探测的独立会话。旧固件/网关无 key 时可能复用同源 IP 的控制会话，
            // 无法判定归属则交给设备闲置超时回收，禁止主动断开。
            if (connected && sessionKey != null && !sessionKey.isBlank()) {
                disconnectRrf(ipAddress, sessionKey);
            }
        }
    }

    private void disconnectRrf(String ipAddress, String sessionKey) {
        try {
            restClient.get()
                    .uri("http://" + ipAddress + "/rr_disconnect")
                    .headers(headers -> {
                        if (sessionKey != null && !sessionKey.isBlank()) {
                            headers.set("X-Session-Key", sessionKey);
                        }
                    })
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
            // 协议已确认，释放会话失败不能覆盖探测结果；日志不得输出会话密钥。
        }
    }

    private JsonNode parse(String body) throws Exception {
        return body == null || body.isBlank() ? null : objectMapper.readTree(body);
    }
}
