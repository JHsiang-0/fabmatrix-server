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

    private static final int STATUS_MAX_ATTEMPTS = 2;
    private static final long STATUS_RETRY_BACKOFF_MILLIS = 100L;
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(30);

    private final RestClient restClient;
    private final RestClient uploadRestClient;
    private final ObjectMapper objectMapper;

    public RrfApiClient() {
        this(defaultRestClient(CONTROL_TIMEOUT), defaultRestClient(UPLOAD_TIMEOUT));
    }

    RrfApiClient(RestClient restClient) {
        this(restClient, restClient);
    }

    RrfApiClient(RestClient restClient, RestClient uploadRestClient) {
        this.restClient = restClient;
        this.uploadRestClient = uploadRestClient;
        this.objectMapper = new ObjectMapper();
    }

    public RrfStatusResponse getStatus(PrinterEndpoint endpoint) {
        requireEndpoint(endpoint, PrinterOperation.GET_STATUS);
        PrinterProtocolException lastFailure = null;
        for (int attempt = 1; attempt <= STATUS_MAX_ATTEMPTS; attempt++) {
            try {
                return getStatusOnce(endpoint);
            } catch (PrinterProtocolException exception) {
                lastFailure = exception;
                if (attempt == STATUS_MAX_ATTEMPTS || !isTransient(exception)) {
                    throw exception;
                }
                sleepBeforeStatusRetry();
            }
        }
        throw lastFailure;
    }

    /**
     * 读取 RRF 网络接口身份。MAC 属于低频对象模型字段，因此必须使用 verbose 标志。
     *
     * <p>设备可能同时暴露禁用的有线接口和活动的 Wi-Fi 接口；这里只接受
     * actualIP 与当前访问地址一致的接口，避免把另一块网卡的 MAC 绑定到设备记录。</p>
     * <p>仅释放返回独立 session key 的身份会话；无 key 的旧固件可能复用同源 IP
     * 的现有控制会话，禁止主动断开，交给设备闲置超时回收。</p>
     */
    public RrfNetworkIdentity getNetworkIdentity(PrinterEndpoint endpoint) {
        requireEndpoint(endpoint, PrinterOperation.GET_STATUS);
        boolean connected = false;
        Session session = null;
        try {
            session = connect(endpoint, PrinterOperation.GET_STATUS);
            connected = true;
            JsonNode network = resultNode(getModel(endpoint, session, "network", "vn"));
            JsonNode interfaces = network.path("interfaces");
            if (!interfaces.isArray()) {
                return null;
            }

            RrfNetworkIdentity fallback = null;
            for (JsonNode networkInterface : interfaces) {
                String actualIp = text(networkInterface, "actualIP");
                String macAddress = text(networkInterface, "mac");
                if (!endpoint.ipAddress().equals(actualIp) || macAddress == null || macAddress.isBlank()) {
                    continue;
                }
                RrfNetworkIdentity identity = new RrfNetworkIdentity(actualIp, macAddress);
                if ("active".equalsIgnoreCase(text(networkInterface, "state"))) {
                    return identity;
                }
                fallback = identity;
            }
            return fallback;
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(PrinterOperation.GET_STATUS, classify(exception), "读取 RRF 网络身份失败", exception);
        } finally {
            if (connected && session != null && session.key() != null && !session.key().isBlank()) {
                disconnect(endpoint, session);
            }
        }
    }

    private RrfStatusResponse getStatusOnce(PrinterEndpoint endpoint) {
        boolean connected = false;
        Session session = null;
        try {
            session = connect(endpoint, PrinterOperation.GET_STATUS);
            connected = true;
            JsonNode stateResponse = getModel(endpoint, session, "state");
            JsonNode jobResponse = getModel(endpoint, session, "job");
            JsonNode heatResponse = getModel(endpoint, session, "heat");
            JsonNode toolsResponse = getModel(endpoint, session, "tools");

            JsonNode state = resultNode(stateResponse);
            JsonNode job = resultNode(jobResponse);
            JsonNode heat = resultNode(heatResponse);
            JsonNode tools = resultNode(toolsResponse);
            JsonNode file = child(job, "file");
            BigDecimal fileSize = decimal(file, "size");
            BigDecimal filePosition = decimal(job, "filePosition");
            BigDecimal progress = percentage(filePosition, fileSize);
            TemperatureSnapshot temperatures = temperatures(state, heat, tools);

            return new RrfStatusResponse(
                    text(state, "status"),
                    null,
                    firstText(text(file, "fileName"), text(job, "lastFileName")),
                    progress,
                    temperatures.toolCurrent(),
                    temperatures.toolTarget(),
                    temperatures.bedCurrent(),
                    temperatures.bedTarget(),
                    decimal(job, "duration"),
                    decimal(file, "printTime"),
                    decimal(job, "rawExtrusion"),
                    filePosition(job),
                    fileSize,
                    decimalOrNested(job, "timesLeft", "file"),
                    booleanValue(job, "lastFileCancelled"),
                    booleanValue(job, "lastFileAborted"),
                    decimal(job, "lastDuration"),
                    firstDecimal(decimal(state, "upTime"), decimal(state, "uptime"))
            );
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(PrinterOperation.GET_STATUS, classify(exception), "解析 RRF 状态失败", exception);
        } finally {
            if (connected) {
                disconnect(endpoint, session);
            }
        }
    }

    /**
     * 状态读取是幂等的，允许一次短暂重连；控制命令不复用该策略，避免网络超时后重复执行命令。
     */
    private boolean isTransient(PrinterProtocolException exception) {
        return exception.getCategory() == FailureCategory.OFFLINE
                || exception.getCategory() == FailureCategory.TIMEOUT;
    }

    private void sleepBeforeStatusRetry() {
        try {
            Thread.sleep(STATUS_RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(PrinterOperation.GET_STATUS, FailureCategory.UNKNOWN,
                    "RRF 状态重试被中断", exception);
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
            ensureSuccessfulGcodeResult(response, operation);
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
            var request = uploadRestClient.post()
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
            startFile(endpoint, safeFilename);
        }
    }

    public void startFile(PrinterEndpoint endpoint, String filename) {
        String safeFilename = safeFilename(filename, PrinterOperation.START_PRINT);
        executeGcode(endpoint, "M32 \"0:/gcodes/" + safeFilename + "\"", PrinterOperation.START_PRINT);
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
        return getModel(endpoint, session, key, null);
    }

    private JsonNode getModel(PrinterEndpoint endpoint, Session session, String key, String flags) {
        try {
            URI modelUri = flags == null || flags.isBlank()
                    ? uri(endpoint, "/rr_model", "key", key)
                    : uri(endpoint, "/rr_model", "key", key, "flags", flags);
            String response = restClient.get()
                    .uri(modelUri)
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

    private void ensureSuccessfulGcodeResult(String response, PrinterOperation operation) {
        JsonNode root = parse(response, operation, "解析 RRF G-code 响应失败");
        JsonNode error = root.get("err");
        if (error != null && error.canConvertToInt()) {
            int errorCode = error.asInt();
            if (errorCode == 0) {
                return;
            }
            FailureCategory category = errorCode == 1
                    ? FailureCategory.REJECTED : FailureCategory.PROTOCOL_ERROR;
            throw failure(operation, category, "RRF 设备拒绝执行 G-code", null);
        }

        // RRF 官方 rr_gcode 成功响应只有 buff 字段，表示命令已进入设备缓冲区。
        // 部分兼容网关额外返回 err=0，因此上方继续兼容该形式。
        JsonNode bufferSpace = root.get("buff");
        if (bufferSpace != null && bufferSpace.canConvertToInt() && bufferSpace.asInt() >= 0) {
            return;
        }
        throw failure(operation, FailureCategory.PROTOCOL_ERROR,
                "RRF G-code 响应缺少 buff/err 成功标记", null);
    }

    private TemperatureSnapshot temperatures(JsonNode state, JsonNode heat, JsonNode tools) {
        JsonNode heaters = heat.path("heaters");
        if (!heaters.isArray() || heaters.isEmpty()) {
            return TemperatureSnapshot.empty();
        }

        int toolHeaterIndex = toolHeaterIndex(state, tools);
        int bedHeaterIndex = firstConfiguredIndex(heat.path("bedHeaters"));
        if (bedHeaterIndex < 0) {
            // 部分 RRF 3.7 beta 固件会返回全 -1 的 bedHeaters；此时使用未被当前工具占用的
            // 第一个有效加热器作为热床，兼容常见的 heater0=热床、heater1=喷头布局。
            bedHeaterIndex = firstHeaterExcept(heaters, toolHeaterIndex);
        }

        HeaterTemperature tool = heaterTemperature(heaters, toolHeaterIndex);
        HeaterTemperature bed = heaterTemperature(heaters, bedHeaterIndex);
        return new TemperatureSnapshot(tool.current(), tool.target(), bed.current(), bed.target());
    }

    private int toolHeaterIndex(JsonNode state, JsonNode tools) {
        if (!tools.isArray()) {
            return -1;
        }
        int currentTool = state.path("currentTool").asInt(Integer.MIN_VALUE);
        JsonNode fallback = null;
        for (JsonNode tool : tools) {
            if (!tool.path("heaters").isArray() || tool.path("heaters").isEmpty()) {
                continue;
            }
            if (fallback == null) {
                fallback = tool;
            }
            if (tool.path("number").asInt(Integer.MIN_VALUE) == currentTool) {
                return firstConfiguredIndex(tool.path("heaters"));
            }
        }
        return fallback == null ? -1 : firstConfiguredIndex(fallback.path("heaters"));
    }

    private int firstConfiguredIndex(JsonNode indices) {
        if (!indices.isArray()) {
            return -1;
        }
        for (JsonNode value : indices) {
            if (value.canConvertToInt() && value.asInt() >= 0) {
                return value.asInt();
            }
        }
        return -1;
    }

    private int firstHeaterExcept(JsonNode heaters, int excludedIndex) {
        for (int index = 0; index < heaters.size(); index++) {
            if (index != excludedIndex && heaters.path(index).path("sensor").asInt(-1) >= 0) {
                return index;
            }
        }
        return -1;
    }

    private HeaterTemperature heaterTemperature(JsonNode heaters, int index) {
        if (index < 0 || index >= heaters.size()) {
            return HeaterTemperature.empty();
        }
        JsonNode heater = heaters.path(index);
        BigDecimal current = decimal(heater, "current");
        String state = text(heater, "state");
        BigDecimal target = "standby".equalsIgnoreCase(state)
                ? decimal(heater, "standby") : decimal(heater, "active");
        if (target == null) {
            target = firstDecimal(decimal(heater, "active"), decimal(heater, "standby"));
        }
        return new HeaterTemperature(current, target);
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

    /** RRF 的 job.timesLeft 是对象，兼容旧网关直接返回数字的形式。 */
    private BigDecimal decimalOrNested(JsonNode parent, String field, String nestedField) {
        BigDecimal direct = decimal(parent, field);
        if (direct != null) {
            return direct;
        }
        JsonNode value = parent.path(field).path(nestedField);
        return value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.decimalValue();
    }

    private BigDecimal firstDecimal(BigDecimal first, BigDecimal fallback) {
        return first == null ? fallback : first;
    }

    private String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private BigDecimal percentage(BigDecimal position, BigDecimal size) {
        if (position == null || size == null || size.signum() <= 0) {
            return null;
        }
        return position.multiply(BigDecimal.valueOf(100))
                .divide(size, 2, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal filePosition(JsonNode job) {
        return decimal(job, "filePosition");
    }

    private Boolean booleanValue(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asBoolean();
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

    private static RestClient defaultRestClient(Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    private record Session(String key) {
    }

    public record RrfNetworkIdentity(String actualIp, String macAddress) {
    }

    private record HeaterTemperature(BigDecimal current, BigDecimal target) {
        private static HeaterTemperature empty() {
            return new HeaterTemperature(null, null);
        }
    }

    private record TemperatureSnapshot(BigDecimal toolCurrent, BigDecimal toolTarget,
                                       BigDecimal bedCurrent, BigDecimal bedTarget) {
        private static TemperatureSnapshot empty() {
            return new TemperatureSnapshot(null, null, null, null);
        }
    }
}
