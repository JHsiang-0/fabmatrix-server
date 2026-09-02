package com.example.farm.common.utils;

import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 与 Klipper(Moonraker) 通信的 HTTP 客户端工具类
 */
@Slf4j
@Component
public class MoonrakerApiClient {

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public MoonrakerApiClient() {
        // 核心修复点 1：设置 3 秒连接超时
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // 核心修复点 2：设置 3 秒读取超时 (防止设备连上后不回传数据导致线程假死)
        factory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    // ... 下面的所有业务方法 (getPrinterStatus, emergencyStop 等) 完全保持不变 ...

    public MoonrakerStatusDTO getPrinterStatus(String ipAddress) {
        // 使用新的对象级查询 URL，获取更全面的打印机数据
        String url = String.format("http://%s:7125/printer/objects/query?webhooks&print_stats&extruder&heater_bed&display_status", ipAddress);

        try {
            String jsonResponse = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (jsonResponse != null) {
                JsonNode rootNode = mapper.readTree(jsonResponse);
                if (rootNode.has("result")) {
                    JsonNode statusNode = rootNode.path("result").path("status");
                    MoonrakerStatusDTO dto = new MoonrakerStatusDTO();

                    // ========== 解析 webhooks 节点 (系统级状态 - 最高优先级) ==========
                    JsonNode webhooks = statusNode.path("webhooks");
                    String webhooksState = getTextOrDefault(webhooks, "state", "unknown");
                    String webhooksStateMessage = getTextOrDefault(webhooks, "state_message", null);
                    dto.setSystemState(webhooksState);
                    dto.setSystemMessage(webhooksStateMessage);

                    // ========== 解析 print_stats 节点 (任务级状态) ==========
                    JsonNode printStats = statusNode.path("print_stats");
                    String printStatsState = getTextOrDefault(printStats, "state", "offline");
                    dto.setFilename(getTextOrDefault(printStats, "filename", null));
                    dto.setTotalDuration(getDoubleOrDefault(printStats, "total_duration", 0.0));
                    dto.setPrintDuration(getDoubleOrDefault(printStats, "print_duration", 0.0));
                    dto.setFilamentUsed(getDoubleOrDefault(printStats, "filament_used", 0.0));

                    // ========== 状态降维融合：根据优先级计算统一状态 ==========
                    String unifiedState = calculateUnifiedState(webhooksState, printStatsState);
                    dto.setState(unifiedState);

                    // ========== 解析 extruder 节点 ==========
                    JsonNode extruder = statusNode.path("extruder");
                    dto.setToolTemperature(getDoubleOrDefault(extruder, "temperature", 0.0));
                    dto.setToolTarget(getDoubleOrDefault(extruder, "target", 0.0));

                    // ========== 解析 heater_bed 节点 ==========
                    JsonNode bed = statusNode.path("heater_bed");
                    dto.setBedTemperature(getDoubleOrDefault(bed, "temperature", 0.0));
                    dto.setBedTarget(getDoubleOrDefault(bed, "target", 0.0));

                    // ========== 解析 display_status 节点 ==========
                    JsonNode displayStatus = statusNode.path("display_status");
                    double rawProgress = getDoubleOrDefault(displayStatus, "progress", 0.0);
                    // 将 0-1 的进度转换为 0-100 的百分比，保留两位小数
                    dto.setProgress(Math.round(rawProgress * 10000.0) / 100.0);

                    return dto;
                }
            }
        } catch (Exception e) {
            log.debug("打印机状态探测失败: 设备IP={}，可能是连接拒绝或超时", ipAddress);
        }
        return null;
    }

    /**
     * 状态降维融合：根据 Klipper 状态字典优先级计算统一状态
     *
     * 【第一层：系统级状态 (webhooks.state) - 最高优先级】
     * - "ready"：主板正常运行（只有在此状态下，才允许去读取第二层的任务状态）
     * - "startup"：Klipper 服务正在启动中
     * - "shutdown"：Klipper 触发了安全保护（如加热器异常、MCU 断开），强制急停
     * - "error"：Klipper 遇到致命配置或连接错误
     *
     * 【第二层：任务级状态 (print_stats.state) - 业务优先级】
     * - "standby"：打印机空闲，无任务
     * - "printing"：正在执行打印任务
     * - "paused"：打印已暂停
     * - "complete"：打印任务顺利完成
     * - "error" / "cancelled"：打印任务被取消或失败
     *
     * @param webhooksState 系统级状态
     * @param printStatsState 任务级状态
     * @return 统一状态（前端展示的唯一决定性状态）
     */
    private String calculateUnifiedState(String webhooksState, String printStatsState) {
        // 防御性编程：处理 null 或空字符串
        String normalizedWebhookState = (webhooksState == null || webhooksState.trim().isEmpty())
                ? "unknown" : webhooksState.toLowerCase().trim();
        String normalizedPrintStatsState = (printStatsState == null || printStatsState.trim().isEmpty())
                ? "offline" : printStatsState.toLowerCase().trim();

        // 优先拦截：如果 webhooks.state 是非 ready 状态，直接使用系统级状态
        // 这包括：shutdown（热失控等安全保护）、error（配置错误）、startup（启动中）
        if (!"ready".equals(normalizedWebhookState)) {
            return normalizedWebhookState;
        }

        // 正常放行：只有当 webhooks.state = "ready" 时，才使用任务级状态
        return normalizedPrintStatsState;
    }

    /**
     * 安全获取 JsonNode 中的文本值，如果节点不存在或为 null，返回默认值
     */
    private String getTextOrDefault(JsonNode parentNode, String fieldName, String defaultValue) {
        if (parentNode == null || parentNode.isMissingNode()) {
            return defaultValue;
        }
        JsonNode fieldNode = parentNode.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return defaultValue;
        }
        return fieldNode.asText(defaultValue);
    }

    /**
     * 安全获取 JsonNode 中的 double 值，如果节点不存在或为 null，返回默认值
     */
    private Double getDoubleOrDefault(JsonNode parentNode, String fieldName, Double defaultValue) {
        if (parentNode == null || parentNode.isMissingNode()) {
            return defaultValue;
        }
        JsonNode fieldNode = parentNode.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return defaultValue;
        }
        return fieldNode.asDouble(defaultValue);
    }

    // ... emergencyStop, pausePrint, uploadAndPrint 保持你原有的写法 ...
    public boolean emergencyStop(String ipAddress) {
        // 你的原有逻辑
        String url = String.format("http://%s:7125/printer/emergency_stop", ipAddress);
        try {
            restClient.post().uri(url).retrieve().toBodilessEntity();
            log.info("已发送急停指令: 打印机IP={}", ipAddress);
            return true;
        } catch (Exception e) {
            log.error("发送急停指令失败: 打印机IP={}，原因={}", ipAddress, e.getMessage());
            return false;
        }
    }

    public boolean pausePrint(String ipAddress) {
        // 你的原有逻辑
        String url = String.format("http://%s:7125/printer/print/pause", ipAddress);
        try {
            restClient.post().uri(url).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("发送暂停指令失败: 打印机IP={}，原因={}", ipAddress, e.getMessage());
            return false;
        }
    }

    /**
     * 恢复已暂停的打印任务。
     */
    public boolean resumePrint(String ipAddress) {
        String url = String.format("http://%s:7125/printer/print/resume", ipAddress);
        try {
            restClient.post().uri(url).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("发送恢复打印指令失败: 打印机IP={}，原因={}", ipAddress, e.getMessage());
            return false;
        }
    }

    /**
     * 取消当前打印任务
     *
     * @param ipAddress 打印机 IP 地址
     * @return 是否成功
     */
    public boolean cancelPrint(String ipAddress) {
        String url = String.format("http://%s:7125/printer/print/cancel", ipAddress);
        try {
            restClient.post().uri(url).retrieve().toBodilessEntity();
            log.info("已发送取消打印指令: 打印机IP={}", ipAddress);
            return true;
        } catch (Exception e) {
            log.error("发送取消打印指令失败: 打印机IP={}，原因={}", ipAddress, e.getMessage());
            return false;
        }
    }

    public boolean uploadAndPrint(String ipAddress, org.springframework.core.io.Resource gcodeResource, String filename) {
        // 你的原有逻辑
        String targetUrl = String.format("http://%s:7125/server/files/upload", ipAddress);
        try {
            org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", gcodeResource);
            body.add("filename", filename != null ? filename : "farm_print.gcode");
            body.add("print", "true");

            log.info("开始下发打印任务: 打印机IP={}，文件名={}", ipAddress, filename);
            String response = restClient.post()
                    .uri(targetUrl)
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            log.info("打印任务下发成功: 打印机IP={}，响应={}", ipAddress, response);
            return true;
        } catch (Exception e) {
            log.error("打印任务下发失败: 打印机IP={}，原因={}", ipAddress, e.getMessage());
            return false;
        }
    }

    /**
     * 上传 G-code 文件到 Moonraker，可选是否立即开始打印
     *
     * @param ipAddress   打印机 IP 地址
     * @param apiKey      API Key（可选，为空时不设置请求头）
     * @param gcodeResource G-code 文件资源
     * @param filename    文件名
     * @param startPrint  是否立即开始打印
     * @return 是否成功
     * @throws Exception 连接失败时抛出异常
     */
    public boolean uploadFile(String ipAddress, String apiKey,
                              org.springframework.core.io.Resource gcodeResource,
                              String filename, boolean startPrint) throws Exception {
        String targetUrl = String.format("http://%s:7125/server/files/upload", ipAddress);

        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", gcodeResource);
        body.add("filename", filename != null ? filename : "farm_print.gcode");
        body.add("print", startPrint ? "true" : "false");

        log.info("上传文件到打印机: ip={}, filename={}, startPrint={}", ipAddress, filename, startPrint);

        var requestBuilder = restClient.post()
                .uri(targetUrl)
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(body);

        // 添加 API Key 请求头（如果提供）
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("X-Api-Key", apiKey);
        }

        String response = requestBuilder.retrieve().body(String.class);
        log.info("文件上传响应: ip={}, response={}", ipAddress, response);
        return true;
    }
}
