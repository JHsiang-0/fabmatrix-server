package com.example.farm.protocol.model;

import java.time.Instant;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.UUID;
import java.io.Serializable;

/** 从统一模型派生的只读摘要，供列表和实时通道使用。 */
public record CanonicalPrinterSummary(int modelVersion, String localPrinterId, long observationRevision,
        long descriptorRevision, Instant serverTime, Instant observedAt, boolean stale,
        String connectionStatus, boolean monitorEnabled, String status, String systemState, String operationState,
        Instant lastSeenAt, long consecutiveFailureCount, Double progress,
        Double toolTemperature, Double toolTarget, Double bedTemperature, Double bedTarget,
        Double printDuration, Double totalDuration, Double filamentUsed,
        String filename, String taskSource, UUID executionId) implements Serializable {
    private static final Duration MAX_AGE = Duration.ofSeconds(30);

    public static CanonicalPrinterSummary unavailable(String printerId, boolean monitorEnabled, String knownHealth,
            Instant lastSeenAt, long failures, Instant now) {
        String health = monitorEnabled && "OFFLINE".equalsIgnoreCase(knownHealth) ? "OFFLINE" : "UNKNOWN";
        return new CanonicalPrinterSummary(2, printerId, 0, 0, now, null, true, health, monitorEnabled,
                health, "UNKNOWN", "UNKNOWN", lastSeenAt, Math.max(0, failures),
                null, null, null, null, null, null, null, null, null, "UNKNOWN", null);
    }

    public static CanonicalPrinterSummary from(CanonicalPrinterModel model, Instant now) {
        var observation = model.observation();
        var connection = observation.connection();
        boolean current = connection.monitorEnabled()
                && connection.status() == CanonicalPrinterModel.ConnectionStatus.ONLINE
                && observation.completeness() != CanonicalPrinterModel.Completeness.INCONSISTENT
                && fresh(observation.observedAt(), now);
        String health = !connection.monitorEnabled() ? "UNKNOWN"
                : connection.status() == CanonicalPrinterModel.ConnectionStatus.OFFLINE ? "OFFLINE"
                : current ? "ONLINE" : "UNKNOWN";
        var tool = heater(model, CanonicalPrinterModel.HeaterRole.TOOL);
        var bed = heater(model, CanonicalPrinterModel.HeaterRole.BED);
        var execution = current ? observation.currentExecution() : null;
        var file = execution == null ? null : CanonicalPrinterModelProjection.safeFile(execution.file());
        String taskSource = !current ? "UNKNOWN" : execution == null ? "NONE" : execution.source().name();
        return new CanonicalPrinterSummary(model.modelVersion(), model.identity().localPrinterId(), observation.revision(),
                observation.descriptorRevision(), now, observation.observedAt(), !current, health, connection.monitorEnabled(),
                current ? operationStatus(observation) : health, observation.systemState().name(), observation.operationState().name(),
                connection.lastSuccessfulAt(), connection.consecutiveFailures(),
                execution == null ? null : number(execution.progressPercent(), now, 0, 100),
                temperature(tool, current, now, false), temperature(tool, current, now, true),
                temperature(bed, current, now, false), temperature(bed, current, now, true),
                execution == null ? null : number(execution.timing().printSeconds(), now, 0, Double.MAX_VALUE),
                execution == null ? null : number(execution.timing().totalSeconds(), now, 0, Double.MAX_VALUE),
                execution == null ? null : number(execution.filamentLengthMm(), now, 0, Double.MAX_VALUE),
                file == null ? null : file.name(), taskSource,
                execution == null ? null : execution.id());
    }

    private static String operationStatus(CanonicalPrinterModel.Observation observation) {
        return switch (observation.systemState()) {
            case FAULT -> "ERROR";
            case STARTING -> "PREPARING";
            case READY -> switch (observation.operationState()) {
                case IDLE -> "IDLE";
                case STARTING, BUSY, CHANGING_TOOL -> "PREPARING";
                case PRINTING, PAUSING, RESUMING, CANCELLING, SIMULATING -> "PRINTING";
                case PAUSED -> "PAUSED";
                case UNKNOWN -> "UNKNOWN";
            };
            case MAINTENANCE, UNKNOWN -> "UNKNOWN";
        };
    }

    private static CanonicalPrinterModel.HeaterState heater(CanonicalPrinterModel model,
                                                            CanonicalPrinterModel.HeaterRole role) {
        var descriptor = model.descriptor();
        String id = descriptor.primaryBedHeaterId();
        if (role == CanonicalPrinterModel.HeaterRole.TOOL) {
            String toolId = model.observation().currentToolId() == null
                    ? descriptor.primaryToolId() : model.observation().currentToolId();
            var tool = descriptor.tools().stream().filter(value -> value.id().equals(toolId)).findFirst().orElse(null);
            if (tool != null) {
                id = descriptor.heaters().stream().filter(value -> value.role() == role && tool.heaterIds().contains(value.id()))
                        .map(CanonicalPrinterModel.HeaterDescriptor::id).findFirst().orElse(null);
            } else if (toolId != null) { return null; }
            else { id = null; }
        }
        if (id == null) {
            var candidates = descriptor.heaters().stream().filter(value -> value.role() == role).toList();
            if (candidates.size() != 1) { return null; }
            id = candidates.getFirst().id();
        }
        String selectedId = id;
        return model.observation().heaters().stream().filter(value -> value.id().equals(selectedId)).findFirst().orElse(null);
    }

    private static Double temperature(CanonicalPrinterModel.HeaterState heater, boolean current, Instant now, boolean target) {
        if (!current || heater == null) { return null; }
        return number(target ? heater.effectiveTargetC() : heater.currentC(), now, -100, 1000);
    }

    private static Double number(CanonicalPrinterModel.Measurement<BigDecimal> measurement,
                                  Instant now, double minimum, double maximum) {
        if (measurement == null || measurement.quality() != CanonicalPrinterModel.Quality.GOOD
                || measurement.value() == null || !fresh(measurement.observedAt(), now)) { return null; }
        double value = measurement.value().doubleValue();
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : null;
    }

    private static boolean fresh(Instant observedAt, Instant now) {
        return observedAt != null && !observedAt.isBefore(now.minus(MAX_AGE)) && !observedAt.isAfter(now.plusSeconds(5));
    }
}
