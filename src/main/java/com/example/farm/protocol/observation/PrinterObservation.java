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
        Execution execution
) {
    public PrinterObservation {
        heaters = List.copyOf(heaters);
        tools = List.copyOf(tools);
    }

    public enum Completeness { COMPLETE, PARTIAL, INCONSISTENT }
    public enum SystemState { READY, STARTING, FAULT, MAINTENANCE, UNKNOWN }
    public enum OperationState { IDLE, PRINTING, PAUSING, PAUSED, RESUMING, CANCELLING, BUSY, CHANGING_TOOL, SIMULATING, UNKNOWN }
    public enum Quality { GOOD, UNAVAILABLE, INVALID, NOT_APPLICABLE }
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

    public record Tool(String id, List<String> heaterIds) {
        public Tool { heaterIds = List.copyOf(heaterIds); }
    }

    /** 文件名仅作为本地观测，不用于证明任务归属或完成。 */
    public record Execution(String filename, boolean simulation, Measurement progressPercent,
                            Measurement printSeconds, Measurement totalSeconds,
                            Measurement activeSeconds, Measurement slicerEstimatedSeconds,
                            Measurement remainingSeconds, Measurement filamentLengthMm) {
        @Override
        public String toString() { return "Execution[filename=redacted, simulation=" + simulation + "]"; }
    }
}
