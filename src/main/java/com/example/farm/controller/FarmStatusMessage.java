package com.example.farm.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Locale;

/**
 * Farm WebSocket 对外消息契约。
 *
 * <p>消息只能使用已登记的类型；构造时同时校验关联 ID、时间戳和数据，
 * 并拒绝把常见敏感字段带入消息树。</p>
 */
public record FarmStatusMessage(
        String type,
        Long printerId,
        long timestamp,
        Object data
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public FarmStatusMessage {
        type = normalizeType(type);
        if (timestamp <= 0) {
            throw new IllegalArgumentException("WebSocket timestamp 必须为正数");
        }
        if (data == null) {
            throw new IllegalArgumentException("WebSocket data 不能为空");
        }
        if (FarmStatusMessageType.SNAPSHOT.name().equals(type)) {
            if (printerId != null) {
                throw new IllegalArgumentException("SNAPSHOT 不应携带 printerId");
            }
        } else if (printerId == null || printerId <= 0) {
            throw new IllegalArgumentException("设备消息必须携带正数 printerId");
        }
        rejectSensitiveFields(data);
    }

    public static FarmStatusMessage printerStatus(Long printerId, Object data) {
        return new FarmStatusMessage(FarmStatusMessageType.PRINTER_STATUS.name(), printerId,
                System.currentTimeMillis(), data);
    }

    public static FarmStatusMessage printerOffline(Long printerId, Object data) {
        return new FarmStatusMessage(FarmStatusMessageType.PRINTER_OFFLINE.name(), printerId,
                System.currentTimeMillis(), data);
    }

    public static FarmStatusMessage jobStatus(Long printerId, Object data) {
        return new FarmStatusMessage(FarmStatusMessageType.JOB_STATUS.name(), printerId,
                System.currentTimeMillis(), data);
    }

    public static FarmStatusMessage snapshot(Object data) {
        return new FarmStatusMessage(FarmStatusMessageType.SNAPSHOT.name(), null,
                System.currentTimeMillis(), data);
    }

    private static String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WebSocket type 不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return FarmStatusMessageType.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的 WebSocket 消息类型: " + value, exception);
        }
    }

    private static void rejectSensitiveFields(Object data) {
        try {
            JsonNode tree = OBJECT_MAPPER.valueToTree(data);
            if (containsSensitiveField(tree)) {
                throw new IllegalArgumentException("WebSocket data 包含敏感字段");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("WebSocket data 无法序列化", exception);
        }
    }

    private static boolean containsSensitiveField(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (isSensitiveField(field.getKey()) || containsSensitiveField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsSensitiveField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSensitiveField(String fieldName) {
        String normalized = fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.equals("apikey")
                || normalized.equals("rustfskey")
                || normalized.equals("password")
                || normalized.equals("token")
                || normalized.equals("sessionkey");
    }
}
