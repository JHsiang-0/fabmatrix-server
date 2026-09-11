package com.example.farm.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Locale;
import java.util.UUID;

/**
 * Farm WebSocket 对外消息契约。
 *
 * <p>消息只能使用已登记的类型；构造时同时校验关联 ID、时间戳和数据，
 * 并拒绝把常见敏感字段带入消息树。</p>
 */
public record FarmStatusMessage(
        String version,
        String type,
        String eventId,
        long sequence,
        @JsonSerialize(using = ToStringSerializer.class) Long printerId,
        long timestamp,
        Object data,
        @JsonIgnore Long audienceUserId
) {

    public static final String CURRENT_VERSION = "1";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public FarmStatusMessage {
        version = version == null || version.isBlank() ? CURRENT_VERSION : version.trim();
        type = normalizeType(type);
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("WebSocket eventId 不能为空");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("WebSocket sequence 必须为正数");
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("WebSocket timestamp 必须为正数");
        }
        if (data == null) {
            throw new IllegalArgumentException("WebSocket data 不能为空");
        }
        data = normalizeSemanticIds(data);
        if (FarmStatusMessageType.SNAPSHOT.name().equals(type)
                || FarmStatusMessageType.LAYOUT_UPDATED.name().equals(type)) {
            if (printerId != null) {
                throw new IllegalArgumentException(type + " 不应携带 printerId");
            }
            if (FarmStatusMessageType.LAYOUT_UPDATED.name().equals(type)) {
                validateLayoutEvent(data);
            }
        } else if (!FarmStatusMessageType.JOB_STATUS.name().equals(type)
                && (printerId == null || printerId <= 0)) {
            throw new IllegalArgumentException("设备消息必须携带正数 printerId");
        }
        rejectSensitiveFields(data);
    }

    /** 兼容现有服务端调用方的构造函数，统一补充契约版本。 */
    public FarmStatusMessage(String type, Long printerId, long timestamp, Object data) {
        this(CURRENT_VERSION, type, UUID.randomUUID().toString(), 1L, printerId, timestamp, data, null);
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

    /** 带任务归属的内部事件；audienceUserId 由 WebSocketServer 过滤且不会序列化。 */
    public static FarmStatusMessage jobStatusForUser(Long printerId, Long audienceUserId, Object data) {
        return new FarmStatusMessage(CURRENT_VERSION, FarmStatusMessageType.JOB_STATUS.name(),
                UUID.randomUUID().toString(), 1L, printerId, System.currentTimeMillis(),
                data, audienceUserId);
    }

    /** WebSocketServer 在获得目标会话发送锁后分配该会话序列。 */
    public FarmStatusMessage withSequence(long nextSequence) {
        return new FarmStatusMessage(version, type, eventId, nextSequence, printerId, timestamp, data,
                audienceUserId);
    }

    public static FarmStatusMessage snapshot(Object data) {
        return new FarmStatusMessage(FarmStatusMessageType.SNAPSHOT.name(), null,
                System.currentTimeMillis(), data);
    }

    public static FarmStatusMessage layoutUpdated(long layoutVersion) {
        if (layoutVersion <= 0) {
            throw new IllegalArgumentException("layoutVersion 必须为正数");
        }
        return new FarmStatusMessage(FarmStatusMessageType.LAYOUT_UPDATED.name(), null,
                System.currentTimeMillis(), java.util.Map.of(
                        "stateSource", "LAYOUT", "layoutVersion", layoutVersion));
    }

    private static void validateLayoutEvent(Object data) {
        if (!(data instanceof java.util.Map<?, ?> map)
                || !"LAYOUT".equals(map.get("stateSource"))
                || !(map.get("layoutVersion") instanceof Number version)
                || version.longValue() <= 0) {
            throw new IllegalArgumentException("LAYOUT_UPDATED data 必须包含有效的 stateSource 和 layoutVersion");
        }
    }

    private static Object normalizeSemanticIds(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            java.util.Map<Object, Object> normalized = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(key, isSemanticIdField(String.valueOf(key))
                    ? normalizeSemanticIdValue(item) : normalizeSemanticIds(item)));
            return normalized;
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.stream().map(FarmStatusMessage::normalizeSemanticIds).toList();
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            java.util.List<Object> normalized = new java.util.ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                normalized.add(normalizeSemanticIds(java.lang.reflect.Array.get(value, index)));
            }
            return normalized;
        }
        return value;
    }

    private static Object normalizeSemanticIdValue(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            return normalizeSemanticIds(map);
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.stream().map(FarmStatusMessage::normalizeSemanticIdValue).toList();
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            java.util.List<Object> normalized = new java.util.ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                normalized.add(normalizeSemanticIdValue(java.lang.reflect.Array.get(value, index)));
            }
            return normalized;
        }
        if (value == null || value instanceof String) return value;
        return String.valueOf(value);
    }

    private static boolean isSemanticIdField(String name) {
        return switch (name) {
            case "id", "printerId", "currentJobId", "jobId", "fileId", "userId", "operatorId",
                    "conflicts", "outOfBoundsPrinters", "changedPositions", "unallocatedPrinters" -> true;
            default -> false;
        };
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
