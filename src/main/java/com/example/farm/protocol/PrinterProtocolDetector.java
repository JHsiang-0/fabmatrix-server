package com.example.farm.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
        if (probeKlipper(ipAddress)) {
            return PrinterProtocolType.KLIPPER;
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
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean probeRrf(String ipAddress) {
        try {
            String body = restClient.get()
                    .uri("http://" + ipAddress + "/rr_connect?password=&sessionKey=yes")
                    .retrieve()
                    .body(String.class);
            JsonNode root = parse(body);
            return root != null && root.has("err");
        } catch (Exception ignored) {
            return false;
        }
    }

    private JsonNode parse(String body) throws Exception {
        return body == null || body.isBlank() ? null : objectMapper.readTree(body);
    }
}
