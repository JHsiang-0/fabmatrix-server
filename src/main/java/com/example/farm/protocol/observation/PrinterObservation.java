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
        List<Sensor> sensors,
        TerminalEvidence terminalEvidence
) {
    public PrinterObservation {
        heaters = List.copyOf(heaters);
        tools = List.copyOf(tools);
        fans = List.copyOf(fans);
        sensors = List.copyOf(sensors);
    }

    public PrinterObservation(Instant captureStartedAt, Instant observedAt, Completeness completeness,
            SystemState systemState, OperationState operationState, List<Heater> heaters,
            List<Tool> tools, String currentToolId, Execution execution, List<Fan> fans,
            Motion motion, List<Sensor> sensors) {
        this(captureStartedAt, observedAt, completeness, systemState, operationState, heaters, tools,
                currentToolId, execution, fans, motion, sensors, null);
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
                         Measurement activeTargetC, Measurement standbyTargetC, Measurement pwmRatio) {
        public Heater(String id, HeaterRole role, HeaterMode mode, Measurement currentC,
                      Measurement effectiveTargetC, Measurement activeTargetC, Measurement standbyTargetC) {
            this(id, role, mode, currentC, effectiveTargetC, activeTargetC, standbyTargetC, null);
        }
    }

    public record Tool(String id, List<String> heaterIds, List<String> fanIds, List<String> extruderIds) {
        public Tool { heaterIds = List.copyOf(heaterIds); fanIds = List.copyOf(fanIds); extruderIds = List.copyOf(extruderIds); }
        public Tool(String id, List<String> heaterIds, List<String> fanIds) { this(id, heaterIds, fanIds, List.of()); }
        public Tool(String id, List<String> heaterIds) { this(id, heaterIds, List.of()); }
    }

    public enum FanRole { PART, HOTEND, CHAMBER, OTHER, UNKNOWN }
    public record Fan(String id, FanRole role, Measurement pwmRatio, Measurement rpm, Measurement requestedPwmRatio) {
        public Fan(String id, FanRole role, Measurement pwmRatio, Measurement rpm) { this(id, role, pwmRatio, rpm, null); }
    }
    /** Private protocol evidence: never copied into the public canonical descriptor/observation. */
    public record TerminalEvidence(String lastFilename, BigDecimal lastDuration, boolean currentFilePresent) { }
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

    public enum RemainingMethod { FILE, SLICER, UNKNOWN }

    /** 文件名仅作为本地观测，不用于证明任务归属或完成。 */
    public record Execution(String filename, boolean simulation, Measurement progressPercent,
                            Measurement printSeconds, Measurement totalSeconds,
                            Measurement activeSeconds, Measurement slicerEstimatedSeconds,
                            Measurement remainingSeconds, Measurement filamentLengthMm,
                            RemainingMethod remainingMethod, Measurement filePositionBytes, Measurement fileSizeBytes,
                            Measurement currentLayer, Measurement totalLayers,
                            Measurement warmupSeconds, Measurement pausedSeconds) {
        public Execution(String filename, boolean simulation, Measurement progressPercent,
                         Measurement printSeconds, Measurement totalSeconds, Measurement activeSeconds,
                         Measurement slicerEstimatedSeconds, Measurement remainingSeconds, Measurement filamentLengthMm,
                         RemainingMethod remainingMethod) {
            this(filename, simulation, progressPercent, printSeconds, totalSeconds, activeSeconds,
                    slicerEstimatedSeconds, remainingSeconds, filamentLengthMm, remainingMethod,
                    null, null, null, null, null, null);
        }
        public Execution(String filename, boolean simulation, Measurement progressPercent,
                         Measurement printSeconds, Measurement totalSeconds, Measurement activeSeconds,
                         Measurement slicerEstimatedSeconds, Measurement remainingSeconds, Measurement filamentLengthMm) {
            this(filename, simulation, progressPercent, printSeconds, totalSeconds, activeSeconds,
                    slicerEstimatedSeconds, remainingSeconds, filamentLengthMm, RemainingMethod.UNKNOWN);
        }
        @Override
        public String toString() { return "Execution[filename=redacted, simulation=" + simulation + "]"; }
    }
}
