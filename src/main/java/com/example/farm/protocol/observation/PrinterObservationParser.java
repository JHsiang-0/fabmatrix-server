package com.example.farm.protocol.observation;

import com.example.farm.protocol.observation.PrinterObservation.Completeness;
import com.example.farm.protocol.observation.PrinterObservation.Execution;
import com.example.farm.protocol.observation.PrinterObservation.Fan;
import com.example.farm.protocol.observation.PrinterObservation.FanRole;
import com.example.farm.protocol.observation.PrinterObservation.Motion;
import com.example.farm.protocol.observation.PrinterObservation.Axis;
import com.example.farm.protocol.observation.PrinterObservation.Sensor;
import com.example.farm.protocol.observation.PrinterObservation.BooleanMeasurement;
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
        status.fields().forEachRemaining(entry -> {
            String rawId = entry.getKey();
            JsonNode node = entry.getValue();
            if (!node.isObject() || !isKlipperHeater(rawId, node)) return;
            String id = safeId(rawId);
            HeaterRole role = "heater_bed".equals(rawId) ? HeaterRole.BED
                    : rawId.startsWith("extruder") ? HeaterRole.TOOL : HeaterRole.UNKNOWN;
            heaters.add(klipperHeater(id, role, node, end));
            if (role == HeaterRole.TOOL) tools.add(new Tool(id, List.of(id)));
        });
        List<Fan> fans = new ArrayList<>();
        status.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            if (!isKlipperFan(name) || !entry.getValue().isObject()) return;
            FanRole role = name.equals("fan") || name.startsWith("fan_generic ") ? FanRole.PART
                    : name.startsWith("heater_fan ") ? FanRole.HOTEND
                    : name.startsWith("controller_fan ") ? FanRole.OTHER : FanRole.UNKNOWN;
            fans.add(new Fan(safeId(name), role,
                    number(entry.getValue(), "speed", end, ZERO, BigDecimal.ONE),
                    number(entry.getValue(), "rpm", end, ZERO, BigDecimal.valueOf(100_000))));
        });
        Motion motion = klipperMotion(status, end);
        List<Sensor> sensors = new ArrayList<>();
        status.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode node = entry.getValue();
            if (!name.startsWith("filament_switch_sensor ") && !name.startsWith("filament_motion_sensor ")) return;
            if (!node.isObject()) return;
            sensors.add(new Sensor(safeId(name), booleanMeasurement(node, "enabled", end),
                    booleanMeasurement(node, "filament_detected", end)));
        });
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
        // 只映射本次查询实际返回的对象；缺失通道保持缺失，不构造虚拟通道。
        boolean inconsistent = end.isBefore(start);
        return new PrinterObservation(start, end, inconsistent ? Completeness.INCONSISTENT : Completeness.PARTIAL,
                systemState, inconsistent ? OperationState.UNKNOWN : operation,
                heaters, tools, null, inconsistent ? null : execution, fans, motion, sensors);
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
                List<String> fanIds = new ArrayList<>();
                for (JsonNode id : tool.path("fans")) {
                    if (id.canConvertToInt() && id.intValue() >= 0) fanIds.add("fan-" + id.intValue());
                }
                tools.add(new Tool("tool-" + tool.path("number").intValue(), ids, fanIds));
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
        List<Fan> fans = new ArrayList<>();
        JsonNode fanNodes = state.path("fans");
        if (fanNodes.isArray()) {
            if (fanNodes.size() > 16) throw new IllegalArgumentException("Observation fan limit exceeded");
            for (int i = 0; i < fanNodes.size(); i++) {
                JsonNode fan = fanNodes.get(i);
                if (!fan.isObject()) continue;
                BigDecimal actual = decimal(fan, "actualValue");
                BigDecimal rpm = decimal(fan, "rpm");
                fans.add(new Fan("fan-" + i, FanRole.OTHER,
                        actual == null || actual.signum() < 0 ? unknown(end) : ratio(actual, end),
                        rpm == null || rpm.signum() < 0 ? unknown(end)
                                : Measurement.of(rpm, end, ZERO, BigDecimal.valueOf(100_000))));
            }
        }
        Motion motion = rrfMotion(state.path("move"), end);
        List<Sensor> sensors = new ArrayList<>();
        JsonNode monitors = state.path("sensors").path("filamentMonitors");
        if (monitors.isArray()) {
            if (monitors.size() > 32) throw new IllegalArgumentException("Observation sensor limit exceeded");
            for (int i = 0; i < monitors.size(); i++) {
                JsonNode monitor = monitors.get(i);
                if (!monitor.isObject()) continue;
                JsonNode enableMode = monitor.path("enableMode");
                Boolean enabled = enableMode.isIntegralNumber() ? enableMode.intValue() != 0
                        : monitor.path("enabled").isBoolean() ? monitor.path("enabled").booleanValue() : null;
                JsonNode present = monitor.path("filamentPresent");
                Boolean detected = present.isBoolean() ? present.booleanValue() : null;
                sensors.add(new Sensor("filament-" + i,
                        booleanMeasurement(enabled, end), booleanMeasurement(detected, end)));
            }
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
                inconsistent ? null : execution, fans, motion, sensors);
    }

    private static Motion rrfMotion(JsonNode move, Instant at) {
        JsonNode axesNode = move.path("axes");
        if (!axesNode.isArray()) return null;
        if (axesNode.size() > 10) throw new IllegalArgumentException("Observation axis limit exceeded");
        List<Axis> axes = new ArrayList<>();
        for (int i = 0; i < axesNode.size(); i++) {
            JsonNode axis = axesNode.get(i);
            if (!axis.isObject()) continue;
            String letter = text(axis, "letter");
            String id = letter == null ? "axis-" + i : safeId(letter.toLowerCase(Locale.ROOT));
            BigDecimal position = decimal(axis, "machinePosition");
            Boolean homed = axis.has("homed") && axis.path("homed").isBoolean() ? axis.path("homed").booleanValue() : null;
            axes.add(new Axis(id, Measurement.of(position, at, BigDecimal.valueOf(-1_000_000),
                    BigDecimal.valueOf(1_000_000)), new BooleanMeasurement(homed,
                    homed == null ? PrinterObservation.Quality.UNAVAILABLE : PrinterObservation.Quality.GOOD,
                    homed == null ? null : at)));
        }
        return new Motion(PrinterObservation.CoordinateSpace.MACHINE, axes, unknown(at), unknown(at));
    }

    private static Heater klipperHeater(String id, HeaterRole role, JsonNode node, Instant at) {
        Measurement target = number(node, "target", at, TEMPERATURE_MIN, TEMPERATURE_MAX);
        return new Heater(id, role, HeaterMode.UNKNOWN, number(node, "temperature", at, TEMPERATURE_MIN, TEMPERATURE_MAX),
                target, target, unknown(at));
    }

    private static boolean isKlipperHeater(String name, JsonNode node) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return (normalized.equals("extruder") || normalized.matches("extruder[0-9]+")
                || normalized.equals("heater_bed") || normalized.startsWith("heater_generic "))
                && (node.has("temperature") || node.has("target"));
    }

    private static boolean isKlipperFan(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("fan") || normalized.startsWith("fan_generic ")
                || normalized.startsWith("heater_fan ") || normalized.startsWith("controller_fan ");
    }

    private static String safeId(String raw) {
        String normalized = raw.trim().replaceAll("[^A-Za-z0-9._:-]", "_");
        if (normalized.length() <= 64) return normalized;
        return "object_" + java.util.UUID.nameUUIDFromBytes(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Motion klipperMotion(JsonNode status, Instant at) {
        JsonNode move = status.path("gcode_move");
        if (!move.isObject()) return null;
        JsonNode positions = move.path("gcode_position");
        String homed = text(status.path("toolhead"), "homed_axes");
        List<String> axisNames = List.of("x", "y", "z", "e");
        List<Axis> axes = new ArrayList<>();
        for (int index = 0; index < axisNames.size() && index < positions.size(); index++) {
            String axis = axisNames.get(index);
            axes.add(new Axis(axis, number(positions, index, at, BigDecimal.valueOf(-1_000_000),
                    BigDecimal.valueOf(1_000_000)), booleanValue(homed != null && homed.contains(axis), at)));
        }
        return new Motion(PrinterObservation.CoordinateSpace.GCODE, axes,
                number(move, "speed_factor", at, ZERO, BigDecimal.valueOf(10)),
                number(move, "extrude_factor", at, ZERO, BigDecimal.valueOf(10)));
    }

    private static Measurement number(JsonNode node, int index, Instant at, BigDecimal min, BigDecimal max) {
        if (!node.isArray() || index < 0 || index >= node.size()) return unknown(at);
        JsonNode value = node.get(index);
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            return new Measurement(null, PrinterObservation.Quality.INVALID, at);
        }
        return Measurement.of(value.decimalValue(), at, min, max);
    }

    private static BooleanMeasurement booleanMeasurement(JsonNode node, String key, Instant at) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return new BooleanMeasurement(null,
                PrinterObservation.Quality.UNAVAILABLE, null);
        if (!value.isBoolean()) return new BooleanMeasurement(null, PrinterObservation.Quality.INVALID, at);
        return new BooleanMeasurement(value.booleanValue(), PrinterObservation.Quality.GOOD, at);
    }

    private static BooleanMeasurement booleanValue(boolean value, Instant at) {
        return new BooleanMeasurement(value, PrinterObservation.Quality.GOOD, at);
    }
    private static BooleanMeasurement booleanMeasurement(Boolean value, Instant at) {
        return new BooleanMeasurement(value, value == null ? PrinterObservation.Quality.UNAVAILABLE
                : PrinterObservation.Quality.GOOD, value == null ? null : at);
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
    private static Measurement ratio(BigDecimal value, Instant at) { return Measurement.of(value, at, ZERO, BigDecimal.ONE); }
    private static BigDecimal decimal(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }
    private static Measurement unknown(Instant at) { return Measurement.of(null, at, null, null); }
}
