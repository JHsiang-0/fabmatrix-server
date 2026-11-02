package com.example.farm.protocol.observation;

import com.example.farm.protocol.observation.PrinterObservation.Completeness;
import com.example.farm.protocol.observation.PrinterObservation.Execution;
import com.example.farm.protocol.observation.PrinterObservation.Heater;
import com.example.farm.protocol.observation.PrinterObservation.HeaterMode;
import com.example.farm.protocol.observation.PrinterObservation.HeaterRole;
import com.example.farm.protocol.observation.PrinterObservation.Measurement;
import com.example.farm.protocol.observation.PrinterObservation.OperationState;
import com.example.farm.protocol.observation.PrinterObservation.SystemState;
import com.example.farm.protocol.observation.PrinterObservation.Tool;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 固件字段映射；本类不执行网络调用，也不推断任务归属或终态。 */
public final class PrinterObservationParser {
    private static final BigDecimal TEMPERATURE_MIN = BigDecimal.valueOf(-100);
    private static final BigDecimal TEMPERATURE_MAX = BigDecimal.valueOf(1000);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal PERCENT_MAX = BigDecimal.valueOf(100);
    private static final BigDecimal DURATION_MAX = BigDecimal.valueOf(315_576_000L);
    private static final BigDecimal FILAMENT_MAX = BigDecimal.valueOf(1_000_000_000L);

    private PrinterObservationParser() { }

    public static PrinterObservation klipper(JsonNode status, Instant start, Instant end) {
        String system = text(status.path("webhooks"), "state");
        String raw = text(status.path("print_stats"), "state");
        SystemState systemState = switch (normalize(system)) {
            case "ready" -> SystemState.READY;
            case "startup" -> SystemState.STARTING;
            case "error", "shutdown" -> SystemState.FAULT;
            default -> SystemState.UNKNOWN;
        };
        OperationState operation = systemState == SystemState.READY ? switch (normalize(raw)) {
            case "standby", "complete", "cancelled" -> OperationState.IDLE;
            case "printing" -> OperationState.PRINTING;
            case "paused" -> OperationState.PAUSED;
            default -> OperationState.UNKNOWN;
        } : OperationState.UNKNOWN;
        List<Heater> heaters = new ArrayList<>();
        List<Tool> tools = new ArrayList<>();
        JsonNode extruder = status.path("extruder");
        if (extruder.isObject()) {
            heaters.add(klipperHeater("extruder", HeaterRole.TOOL, extruder, end));
            tools.add(new Tool("extruder", List.of("extruder")));
        }
        JsonNode bed = status.path("heater_bed");
        if (bed.isObject()) heaters.add(klipperHeater("heater_bed", HeaterRole.BED, bed, end));
        JsonNode stats = status.path("print_stats");
        Execution execution = null;
        if (operation == OperationState.PRINTING || operation == OperationState.PAUSED) {
            Measurement progressRatio = number(status.path("display_status"), "progress", end, ZERO, BigDecimal.ONE);
            Measurement progress = progressRatio.quality() == PrinterObservation.Quality.GOOD
                    ? percent(progressRatio.value().multiply(PERCENT_MAX), end) : progressRatio;
            execution = new Execution(text(stats, "filename"), false, progress,
                    number(stats, "print_duration", end, ZERO, DURATION_MAX),
                    number(stats, "total_duration", end, ZERO, DURATION_MAX), unknown(end), unknown(end), unknown(end),
                    number(stats, "filament_used", end, ZERO, FILAMENT_MAX));
        }
        // 当前查询仅覆盖固定的主喷嘴与热床，尚未发现完整设备通道。
        boolean inconsistent = end.isBefore(start);
        return new PrinterObservation(start, end, inconsistent ? Completeness.INCONSISTENT : Completeness.PARTIAL,
                systemState, inconsistent ? OperationState.UNKNOWN : operation,
                heaters, tools, null, inconsistent ? null : execution);
    }

    public static PrinterObservation rrf(JsonNode state, JsonNode job, JsonNode heat, JsonNode toolsNode,
                                         Instant start, Instant end) {
        return rrf(state, job, heat, toolsNode, start, end, end, end);
    }

    public static PrinterObservation rrf(JsonNode state, JsonNode job, JsonNode heat, JsonNode toolsNode,
                                         Instant start, Instant jobObservedAt, Instant heatObservedAt, Instant end) {
        String raw = normalize(text(state, "status"));
        SystemState system = switch (raw) {
            case "starting" -> SystemState.STARTING;
            case "updating" -> SystemState.MAINTENANCE;
            case "halted" -> SystemState.FAULT;
            case "idle", "processing", "paused", "pausing", "resuming", "cancelling", "busy", "changingtool", "simulating" -> SystemState.READY;
            default -> SystemState.UNKNOWN;
        };
        OperationState operation = switch (raw) {
            case "idle" -> OperationState.IDLE;
            case "processing" -> OperationState.PRINTING;
            case "paused" -> OperationState.PAUSED;
            case "pausing" -> OperationState.PAUSING;
            case "resuming" -> OperationState.RESUMING;
            case "cancelling" -> OperationState.CANCELLING;
            case "busy" -> OperationState.BUSY;
            case "changingtool" -> OperationState.CHANGING_TOOL;
            case "simulating" -> OperationState.SIMULATING;
            default -> OperationState.UNKNOWN;
        };
        List<Tool> tools = new ArrayList<>();
        if ((toolsNode.isArray() && toolsNode.size() > 8)
                || (heat.path("heaters").isArray() && heat.path("heaters").size() > 16)) {
            throw new IllegalArgumentException("Observation channel limit exceeded");
        }
        if (toolsNode.isArray()) {
            for (JsonNode tool : toolsNode) {
                if (!tool.path("number").canConvertToInt()) continue;
                List<String> ids = new ArrayList<>();
                for (JsonNode id : tool.path("heaters")) {
                    if (id.canConvertToInt() && id.intValue() >= 0) ids.add("heater-" + id.intValue());
                }
                tools.add(new Tool("tool-" + tool.path("number").intValue(), ids));
            }
        }
        List<Heater> heaters = new ArrayList<>();
        JsonNode nodes = heat.path("heaters");
        if (nodes.isArray()) {
            for (int i = 0; i < nodes.size(); i++) {
                JsonNode node = nodes.get(i);
                if (!node.isObject()) continue;
                String id = "heater-" + i;
                boolean isBed = containsIndex(heat.path("bedHeaters"), i);
                boolean isTool = tools.stream().anyMatch(t -> t.heaterIds().contains(id));
                HeaterRole role = isBed && isTool ? HeaterRole.UNKNOWN
                        : isBed ? HeaterRole.BED : isTool ? HeaterRole.TOOL : HeaterRole.UNKNOWN;
                HeaterMode mode = switch (normalize(text(node, "state"))) {
                    case "off" -> HeaterMode.OFF;
                    case "active" -> HeaterMode.ACTIVE;
                    case "standby" -> HeaterMode.STANDBY;
                    case "fault" -> HeaterMode.FAULT;
                    default -> HeaterMode.UNKNOWN;
                };
                Measurement active = number(node, "active", heatObservedAt, TEMPERATURE_MIN, TEMPERATURE_MAX);
                Measurement standby = number(node, "standby", heatObservedAt, TEMPERATURE_MIN, TEMPERATURE_MAX);
                Measurement effective = switch (mode) {
                    case OFF -> temperature(ZERO, heatObservedAt);
                    case ACTIVE -> active;
                    case STANDBY -> standby;
                    default -> unknown(heatObservedAt);
                };
                Measurement current = node.path("sensor").isIntegralNumber() && node.path("sensor").intValue() < 0
                        ? unknown(heatObservedAt) : number(node, "current", heatObservedAt, TEMPERATURE_MIN, TEMPERATURE_MAX);
                heaters.add(new Heater(id, role, mode, current,
                        effective, active, standby));
            }
        }
        String currentTool = state.path("currentTool").canConvertToInt() && state.path("currentTool").intValue() >= 0
                ? "tool-" + state.path("currentTool").intValue() : null;
        if (currentTool != null) {
            String selected = currentTool;
            if (tools.stream().noneMatch(t -> t.id().equals(selected))) currentTool = null;
        }
        Execution execution = null;
        if (operation == OperationState.PRINTING || operation == OperationState.PAUSED
                || operation == OperationState.PAUSING || operation == OperationState.RESUMING
                || operation == OperationState.CANCELLING || operation == OperationState.SIMULATING) {
            JsonNode file = job.path("file");
            Measurement sizeMeasure = number(file, "size", jobObservedAt, ZERO, null);
            Measurement positionMeasure = number(job, "filePosition", jobObservedAt, ZERO, null);
            BigDecimal size = sizeMeasure.value();
            BigDecimal position = positionMeasure.value();
            BigDecimal progress = size != null && size.signum() > 0 && position != null
                    ? position.multiply(PERCENT_MAX).divide(size, 2, RoundingMode.HALF_UP) : null;
            Measurement progressMeasure = sizeMeasure.quality() == PrinterObservation.Quality.INVALID
                    || positionMeasure.quality() == PrinterObservation.Quality.INVALID
                    ? new Measurement(null, PrinterObservation.Quality.INVALID, jobObservedAt) : percent(progress, jobObservedAt);
            execution = new Execution(text(file, "fileName"), operation == OperationState.SIMULATING,
                    progressMeasure, unknown(jobObservedAt), unknown(jobObservedAt), number(job, "duration", jobObservedAt, ZERO, DURATION_MAX),
                    number(file, "printTime", jobObservedAt, ZERO, DURATION_MAX), number(job.path("timesLeft"), "file", jobObservedAt, ZERO, DURATION_MAX),
                    number(job, "rawExtrusion", jobObservedAt, ZERO, FILAMENT_MAX));
        }
        // 当前客户端分四次 HTTP 获取对象，没有任务一致性标记；不可将结果视为原子帧。
        boolean inconsistent = end.isBefore(start) || jobObservedAt.isBefore(start) || heatObservedAt.isBefore(jobObservedAt)
                || end.isBefore(heatObservedAt);
        return new PrinterObservation(start, end, inconsistent ? Completeness.INCONSISTENT : Completeness.PARTIAL,
                system, inconsistent ? OperationState.UNKNOWN : operation, heaters, tools, currentTool,
                inconsistent ? null : execution);
    }

    private static Heater klipperHeater(String id, HeaterRole role, JsonNode node, Instant at) {
        Measurement target = number(node, "target", at, TEMPERATURE_MIN, TEMPERATURE_MAX);
        return new Heater(id, role, HeaterMode.UNKNOWN, number(node, "temperature", at, TEMPERATURE_MIN, TEMPERATURE_MAX),
                target, target, unknown(at));
    }
    private static boolean containsIndex(JsonNode nodes, int index) {
        if (!nodes.isArray()) return false;
        for (JsonNode node : nodes) if (node.canConvertToInt() && node.intValue() == index) return true;
        return false;
    }
    private static Measurement number(JsonNode node, String key, Instant at, BigDecimal min, BigDecimal max) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return unknown(at);
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            return new Measurement(null, PrinterObservation.Quality.INVALID, at);
        }
        return Measurement.of(value.decimalValue(), at, min, max);
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static Measurement temperature(BigDecimal value, Instant at) { return Measurement.of(value, at, TEMPERATURE_MIN, TEMPERATURE_MAX); }
    private static Measurement percent(BigDecimal value, Instant at) { return Measurement.of(value, at, ZERO, PERCENT_MAX); }
    private static Measurement unknown(Instant at) { return Measurement.of(null, at, null, null); }
}
