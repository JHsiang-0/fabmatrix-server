package com.example.farm.protocol.observation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 一次只读采集的固件无关结果；不包含设备地址、凭据或原始响应。 */
public record PrinterObservation(
        Instant captureStartedAt,
        Instant observedAt,
        Completeness completeness,
        SystemState systemState,
        OperationState operationState,
        List<Heater> heaters,
        List<Tool> tools,
        String currentToolId,
        Execution execution,
        List<Fan> fans,
        Motion motion,
        List<Sensor> sensors
) {
    public PrinterObservation {
        heaters = List.copyOf(heaters);
        tools = List.copyOf(tools);
        fans = List.copyOf(fans);
        sensors = List.copyOf(sensors);
    }

    public PrinterObservation(Instant captureStartedAt, Instant observedAt, Completeness completeness,
                              SystemState systemState, OperationState operationState,
                              List<Heater> heaters, List<Tool> tools, String currentToolId, Execution execution) {
        this(captureStartedAt, observedAt, completeness, systemState, operationState, heaters, tools,
                currentToolId, execution, List.of(), null, List.of());
    }

    public enum Completeness { COMPLETE, PARTIAL, INCONSISTENT }
    public enum SystemState { READY, STARTING, FAULT, MAINTENANCE, UNKNOWN }
    public enum OperationState { IDLE, PRINTING, PAUSING, PAUSED, RESUMING, CANCELLING, BUSY, CHANGING_TOOL, SIMULATING, UNKNOWN }
    public enum Quality { GOOD, UNAVAILABLE, UNSUPPORTED, INVALID, NOT_APPLICABLE }
    public enum HeaterRole { TOOL, BED, CHAMBER, OTHER, UNKNOWN }
    public enum HeaterMode { OFF, ACTIVE, STANDBY, FAULT, UNKNOWN }

    /** 数值缺失、越界和真实零值分别表达。 */
    public record Measurement(BigDecimal value, Quality quality, Instant observedAt) {
        public static Measurement of(BigDecimal value, Instant at, BigDecimal min, BigDecimal max) {
            if (value == null) return new Measurement(null, Quality.UNAVAILABLE, null);
            if ((min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0)) {
                return new Measurement(null, Quality.INVALID, at);
            }
            return new Measurement(value, Quality.GOOD, at);
        }
    }

    public record Heater(String id, HeaterRole role, HeaterMode mode,
                         Measurement currentC, Measurement effectiveTargetC,
                         Measurement activeTargetC, Measurement standbyTargetC) { }

    public record Tool(String id, List<String> heaterIds, List<String> fanIds) {
        public Tool { heaterIds = List.copyOf(heaterIds); fanIds = List.copyOf(fanIds); }
        public Tool(String id, List<String> heaterIds) { this(id, heaterIds, List.of()); }
    }

    public enum FanRole { PART, HOTEND, CHAMBER, OTHER, UNKNOWN }
    public record Fan(String id, FanRole role, Measurement pwmRatio, Measurement rpm) { }
    public enum CoordinateSpace { GCODE, MACHINE, UNKNOWN }
    public record Motion(CoordinateSpace coordinateSpace, List<Axis> axes, Measurement speedFactor, Measurement flowFactor) {
        public Motion { axes = List.copyOf(axes); }
        public Motion(List<Axis> axes, Measurement speedFactor, Measurement flowFactor) {
            this(CoordinateSpace.UNKNOWN, axes, speedFactor, flowFactor);
        }
    }
    public record Axis(String id, Measurement positionMm, BooleanMeasurement homed) { }
    public record BooleanMeasurement(Boolean value, Quality quality, Instant observedAt) { }
    public record Sensor(String id, BooleanMeasurement enabled, BooleanMeasurement detected) { }

    /** 文件名仅作为本地观测，不用于证明任务归属或完成。 */
    public record Execution(String filename, boolean simulation, Measurement progressPercent,
                            Measurement printSeconds, Measurement totalSeconds,
                            Measurement activeSeconds, Measurement slicerEstimatedSeconds,
                            Measurement remainingSeconds, Measurement filamentLengthMm) {
        @Override
        public String toString() { return "Execution[filename=redacted, simulation=" + simulation + "]"; }
    }
}
