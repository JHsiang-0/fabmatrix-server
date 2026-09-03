package com.example.farm.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/**
 * RRF HTTP 客户端。
 *
 * <p>调用顺序遵循 RRF 官方 HTTP 会话模型：先 rr_connect，再携带
 * X-Session-Key 调用 rr_model、rr_gcode 或 rr_upload，最后释放会话。</p>
 */
@Component
public class RrfApiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RrfApiClient() {
        this(defaultRestClient());
    }

    RrfApiClient(RestClient restClient) {
        this.restClient = restClient;
        this.objectMapper = new ObjectMapper();
    }

    public RrfStatusResponse getStatus(PrinterEndpoint endpoint) {
        requireEndpoint(endpoint, PrinterOperation.GET_STATUS);
        Session session = connect(endpoint, PrinterOperation.GET_STATUS);
        try {
            JsonNode stateResponse = getModel(endpoint, session, "state");
            JsonNode jobResponse = getModel(endpoint, session, "job");

            JsonNode state = resultNode(stateResponse);
            JsonNode job = resultNode(jobResponse);
            JsonNode file = child(job, "file");
            BigDecimal fileSize = decimal(file, "size");
            BigDecimal filePosition = decimal(job, "filePosition");
            BigDecimal progress = percentage(filePosition, fileSize);

            return new RrfStatusResponse(
                    text(state, "status"),
                    null,
                    text(file, "fileName"),
                    progress,
                    null,
                    null,
                    null,
                    null,
                    decimal(job, "duration"),
                    decimal(file, "printTime"),
                    decimal(job, "rawExtrusion")
            );
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(PrinterOperation.GET_STATUS, classify(exception), "解析 RRF 状态失败", exception);
        } finally {
            disconnect(endpoint, session);
        }
    }

    public void executeGcode(PrinterEndpoint endpoint, String gcode, PrinterOperation operation) {
        requireEndpoint(endpoint, operation);
        if (gcode == null || gcode.isBlank()) {
            throw failure(operation, FailureCategory.REJECTED, "RRF G-code 不能为空", null);
        }
        Session session = connect(endpoint, operation);
        try {
            String response = restClient.get()
                    .uri(uri(endpoint, "/rr_gcode", "gcode", gcode))
                    .headers(headers -> addSessionKey(headers, session))
                    .retrieve()
                    .body(String.class);
            if (response == null) {
                throw failure(operation, FailureCategory.PROTOCOL_ERROR, "RRF 未返回 G-code 响应", null);
            }
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(operation, classify(exception), "执行 RRF G-code 失败", exception);
        } finally {
            disconnect(endpoint, session);
        }
    }

    public void uploadFile(PrinterEndpoint endpoint, Resource file, String filename, boolean startPrint) {
        PrinterOperation operation = startPrint ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE;
        requireEndpoint(endpoint, operation);
        if (file == null) {
            throw failure(operation, FailureCategory.REJECTED, "上传文件不能为空", null);
        }
        String safeFilename = safeFilename(filename, operation);
        Session session = connect(endpoint, operation);
        try {
            var request = restClient.post()
                    .uri(uri(endpoint, "/rr_upload", "name", "0:/gcodes/" + safeFilename))
                    .headers(headers -> addSessionKey(headers, session))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
            long contentLength = file.contentLength();
            if (contentLength >= 0) {
                request.headers(headers -> headers.setContentLength(contentLength));
            }
            String response = request.body(file).retrieve().body(String.class);
            ensureSuccessfulResult(response, operation);
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(operation, classify(exception), "上传文件到 RRF 失败", exception);
        } finally {
            disconnect(endpoint, session);
        }

        if (startPrint) {
            executeGcode(endpoint, "M32 \"0:/gcodes/" + safeFilename + "\"", PrinterOperation.START_PRINT);
        }
    }

    private Session connect(PrinterEndpoint endpoint, PrinterOperation operation) {
        try {
            String response = restClient.get()
                    .uri(uri(endpoint, "/rr_connect", "password", endpoint.apiKey(), "sessionKey", "yes"))
                    .retrieve()
                    .body(String.class);
            JsonNode root = parse(response, operation, "解析 RRF 登录响应失败");
            int errorCode = root.path("err").asInt(-1);
            if (errorCode != 0) {
                FailureCategory category = errorCode == 1
                        ? FailureCategory.REJECTED : FailureCategory.PROTOCOL_ERROR;
                throw failure(operation, category, "RRF 登录失败", null);
            }
            JsonNode sessionKey = root.get("sessionKey");
            // RRF 模拟器/兼容网关可能在 err=0 时不返回 sessionKey，并允许后续请求不带会话头。
            // 真正启用会话的设备仍然返回并使用 X-Session-Key。
            return new Session(sessionKey == null || sessionKey.isNull() || sessionKey.asText().isBlank()
                    ? null : sessionKey.asText());
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(operation, classify(exception), "连接 RRF 设备失败", exception);
        }
    }

    private JsonNode getModel(PrinterEndpoint endpoint, Session session, String key) {
        try {
            String response = restClient.get()
                    .uri(uri(endpoint, "/rr_model", "key", key))
                    .headers(headers -> addSessionKey(headers, session))
                    .retrieve()
                    .body(String.class);
            return parse(response, PrinterOperation.GET_STATUS, "解析 RRF 对象模型失败");
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(PrinterOperation.GET_STATUS, classify(exception), "查询 RRF 对象模型失败", exception);
        }
    }

    private void disconnect(PrinterEndpoint endpoint, Session session) {
        try {
            restClient.get()
                    .uri(uri(endpoint, "/rr_disconnect"))
                    .headers(headers -> addSessionKey(headers, session))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
            // 主操作已经结束，释放会话失败不能覆盖原始结果。
        }
    }

    private void ensureSuccessfulResult(String response, PrinterOperation operation) {
        JsonNode root = parse(response, operation, "解析 RRF 文件上传响应失败");
        int errorCode = root.path("err").asInt(-1);
        if (errorCode != 0) {
            throw failure(operation, FailureCategory.PROTOCOL_ERROR, "RRF 文件上传失败", null);
        }
    }

    private JsonNode parse(String response, PrinterOperation operation, String message) {
        if (response == null || response.isBlank()) {
            throw failure(operation, FailureCategory.PROTOCOL_ERROR, message, null);
        }
        try {
            return objectMapper.readTree(response);
        } catch (IOException exception) {
            throw failure(operation, FailureCategory.PROTOCOL_ERROR, message, exception);
        }
    }

    private JsonNode resultNode(JsonNode response) {
        JsonNode result = response.path("result");
        if (result.isMissingNode() || result.isNull()) {
            return objectMapper.createObjectNode();
        }
        return result;
    }

    private JsonNode child(JsonNode parent, String field) {
        JsonNode direct = parent.path(field);
        if (!direct.isMissingNode()) {
            return direct;
        }
        return parent.path("job").path(field);
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private BigDecimal decimal(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    private BigDecimal percentage(BigDecimal position, BigDecimal size) {
        if (position == null || size == null || size.signum() <= 0) {
            return null;
        }
        return position.multiply(BigDecimal.valueOf(100))
                .divide(size, 2, java.math.RoundingMode.HALF_UP);
    }

    private void requireEndpoint(PrinterEndpoint endpoint, PrinterOperation operation) {
        if (endpoint == null || endpoint.ipAddress() == null || endpoint.ipAddress().isBlank()) {
            throw failure(operation, FailureCategory.REJECTED, "RRF 设备地址未配置", null);
        }
    }

    private void addSessionKey(org.springframework.http.HttpHeaders headers, Session session) {
        if (session != null && session.key() != null && !session.key().isBlank()) {
            headers.set("X-Session-Key", session.key());
        }
    }

    private String safeFilename(String filename, PrinterOperation operation) {
        if (filename == null || filename.isBlank()) {
            throw failure(operation, FailureCategory.REJECTED, "文件名不能为空", null);
        }
        String normalized = filename.replace('\\', '/');
        String safe = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)
                || safe.contains("\"") || safe.contains("\r") || safe.contains("\n")) {
            throw failure(operation, FailureCategory.REJECTED, "RRF 文件名不合法", null);
        }
        return safe;
    }

    private URI uri(PrinterEndpoint endpoint, String path, String... queryParameters) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("http://" + endpoint.ipAddress())
                .path(path);
        for (int i = 0; i < queryParameters.length; i += 2) {
            builder.queryParam(queryParameters[i], queryParameters[i + 1]);
        }
        return builder.build().encode().toUri();
    }

    private FailureCategory classify(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return FailureCategory.TIMEOUT;
            }
            if (current instanceof IOException || current instanceof java.net.ConnectException) {
                return FailureCategory.OFFLINE;
            }
            current = current.getCause();
        }
        return FailureCategory.PROTOCOL_ERROR;
    }

    private PrinterProtocolException failure(PrinterOperation operation,
                                             FailureCategory category,
                                             String message,
                                             Throwable cause) {
        return new PrinterProtocolException(
                operation,
                PrinterProtocolType.RRF,
                category,
                message,
                cause
        );
    }

    private static RestClient defaultRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory).build();
    }

    private record Session(String key) {
    }
}
