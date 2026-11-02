package com.example.farm.protocol.model;

import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.protocol.observation.PrinterObservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.example.farm.protocol.model.CanonicalPrinterModel.*;

/** Maps the currently available local read model without inventing firmware data. */
public final class CanonicalPrinterModelMapper {
    private CanonicalPrinterModelMapper() { }

    public static CanonicalPrinterModel map(Printer printer, UUID sourceInstanceId,
                                            UUID deviceBootId, long observationRevision, long descriptorRevision,
                                            PrinterObservation observation, PrinterExecution activeExecution,
                                            boolean continuityUnknown, Instant capturedAt) {
        return map(printer, sourceInstanceId, deviceBootId, observationRevision, descriptorRevision,
                observation, activeExecution, null, continuityUnknown, capturedAt);
    }

    public static CanonicalPrinterModel map(Printer printer, UUID sourceInstanceId,
                                            UUID deviceBootId, long observationRevision, long descriptorRevision,
                                            PrinterObservation observation, PrinterExecution activeExecution,
                                            PrinterExecution lastFinishedExecution,
                                            boolean continuityUnknown, Instant capturedAt) {
        if (printer == null || printer.getId() == null || printer.getDeviceUid() == null
                || sourceInstanceId == null || deviceBootId == null || observationRevision < 1
                || descriptorRevision < 1 || capturedAt == null) {
            throw new IllegalArgumentException("Printer identity and capture time are required");
        }
        UUID deviceUid;
        try {
            deviceUid = UUID.fromString(printer.getDeviceUid());
        } catch (IllegalArgumentException invalidUid) {
            throw new IllegalArgumentException("Printer deviceUid must be a UUID", invalidUid);
        }
        Descriptor descriptor = descriptor(printer, observation, descriptorRevision);
        Observation mappedObservation = observation(printer, observation, activeExecution, lastFinishedExecution,
                capturedAt, deviceBootId, observationRevision, descriptor.revision(), continuityUnknown);
        CanonicalPrinterModel result = new CanonicalPrinterModel(CURRENT_MODEL_VERSION,
                new Identity(sourceInstanceId, deviceUid, printer.getId().toString()), descriptor, mappedObservation);
        CanonicalPrinterModelValidator.validate(result);
        return result;
    }

    private static Descriptor descriptor(Printer printer, PrinterObservation observation, long revision) {
        List<HeaterDescriptor> heaters = observation == null ? List.of() : observation.heaters().stream()
                .map(heater -> new HeaterDescriptor(heater.id(), heater.id(), HeaterRole.valueOf(heater.role().name()),
                        RoleEvidence.UNKNOWN)).toList();
        List<FanDescriptor> fans = observation == null ? List.of() : observation.fans().stream()
                .map(fan -> new FanDescriptor(fan.id(), fan.id(), FanRole.valueOf(fan.role().name()))).toList();
        var heaterIds = heaters.stream().map(HeaterDescriptor::id).collect(java.util.stream.Collectors.toSet());
        var fanIds = fans.stream().map(FanDescriptor::id).collect(java.util.stream.Collectors.toSet());
        List<ToolDescriptor> tools = observation == null ? List.of() : observation.tools().stream()
                .map(tool -> new ToolDescriptor(tool.id(), tool.id(),
                        tool.heaterIds().stream().filter(heaterIds::contains).toList(),
                        tool.fanIds().stream().filter(fanIds::contains).toList(), List.of())).toList();
        List<AxisDescriptor> axes = observation == null || observation.motion() == null ? List.of()
                : observation.motion().axes().stream().map(axis -> new AxisDescriptor(axis.id(), axis.id())).toList();
        List<SensorDescriptor> sensors = observation == null ? List.of() : observation.sensors().stream()
                .map(sensor -> new SensorDescriptor(sensor.id(), sensor.id(), "FILAMENT", "BOOLEAN")).toList();
        String type = printer.getFirmwareType() == null ? "" : printer.getFirmwareType().toUpperCase(Locale.ROOT);
        FirmwareFamily family = type.contains("RRF") || type.contains("REPRAP") ? FirmwareFamily.RRF
                : type.contains("KLIPPER") ? FirmwareFamily.KLIPPER : FirmwareFamily.UNKNOWN;
        return new Descriptor(revision, printer.getName(), printer.getMachineNumber(), family, null,
                family == FirmwareFamily.KLIPPER ? ConnectionMode.MOONRAKER
                        : family == FirmwareFamily.RRF ? ConnectionMode.RRF_STANDALONE : ConnectionMode.UNKNOWN,
                MachineMode.UNKNOWN, null, null, null,
                tools, heaters, fans, axes, sensors, capabilities(observation), null, printer.getCurrentMaterial(),
                printer.getNozzleSize());
    }

    private static List<Capability> capabilities(PrinterObservation observation) {
        Instant detectedAt = observation == null ? null : observation.observedAt();
        boolean hasHeaters = observation != null && !observation.heaters().isEmpty();
        boolean hasFans = observation != null && !observation.fans().isEmpty();
        boolean hasAxes = observation != null && observation.motion() != null && !observation.motion().axes().isEmpty();
        boolean hasSensors = observation != null && !observation.sensors().isEmpty();
        boolean hasCurrentExecution = observation != null && observation.execution() != null;
        boolean hasFileProgress = hasCurrentExecution && observation.execution().progressPercent().quality()
                == PrinterObservation.Quality.GOOD;
        boolean hasRemaining = hasCurrentExecution && observation.execution().remainingSeconds().quality()
                == PrinterObservation.Quality.GOOD;
        boolean hasFlowFactor = observation != null && observation.motion() != null
                && observation.motion().flowFactor().quality() == PrinterObservation.Quality.GOOD;
        boolean hasTargets = observation != null && observation.heaters().stream()
                .anyMatch(heater -> heater.effectiveTargetC().quality() == PrinterObservation.Quality.GOOD);
        return List.of(
                capability("READ_TEMPERATURE", hasHeaters, detectedAt),
                capability("READ_TARGET_TEMPERATURE", hasTargets, detectedAt),
                capability("READ_CURRENT_EXECUTION", observation != null
                        && observation.operationState() != PrinterObservation.OperationState.UNKNOWN, detectedAt),
                capability("READ_FILE_PROGRESS", hasFileProgress, detectedAt),
                capability("READ_LAYER_PROGRESS", false, detectedAt),
                capability("READ_REMAINING_TIME", hasRemaining, detectedAt),
                capability("READ_FANS", hasFans, detectedAt),
                capability("READ_AXES", hasAxes, detectedAt),
                capability("READ_FILAMENT_SENSORS", hasSensors, detectedAt),
                capability("READ_FLOW_FACTOR", hasFlowFactor, detectedAt));
    }

    private static Capability capability(String code, boolean observed, Instant detectedAt) {
        return new Capability(code, observed ? SupportState.SUPPORTED : SupportState.UNKNOWN,
                observed ? CapabilityEvidence.PROBED : CapabilityEvidence.ADAPTER, detectedAt);
    }

    private static Observation observation(Printer printer, PrinterObservation source,
                                           PrinterExecution activeExecution, PrinterExecution lastFinishedExecution,
                                           Instant capturedAt,
                                           UUID deviceBootId, long observationRevision, long descriptorRevision,
                                           boolean continuityUnknown) {
        Instant started = source == null || source.captureStartedAt() == null ? capturedAt : source.captureStartedAt();
        Instant observed = source == null || source.observedAt() == null ? capturedAt : source.observedAt();
        List<HeaterState> heaters = source == null ? List.of() : source.heaters().stream()
                .map(heater -> new HeaterState(heater.id(), measurement(heater.currentC()),
                        measurement(heater.effectiveTargetC()), measurement(heater.activeTargetC()),
                        measurement(heater.standbyTargetC()), null, HeaterMode.valueOf(heater.mode().name()))).toList();
        List<FanState> fans = source == null ? List.of() : source.fans().stream()
                .map(fan -> new FanState(fan.id(), measurement(fan.pwmRatio()), measurement(fan.rpm()))).toList();
        List<AxisState> axes = source == null || source.motion() == null ? List.of() : source.motion().axes().stream()
                .map(axis -> new AxisState(axis.id(), measurement(axis.positionMm()), booleanMeasurement(axis.homed()))).toList();
        Motion motion = source == null || source.motion() == null
                ? unknownMotion() : new Motion(CoordinateSpace.valueOf(source.motion().coordinateSpace().name()), axes,
                        measurement(source.motion().speedFactor()), measurement(source.motion().flowFactor()));
        List<SensorState> sensors = source == null ? List.of() : source.sensors().stream()
                .map(sensor -> new SensorState(sensor.id(), jsonMeasurement(sensor.enabled()),
                        jsonMeasurement(sensor.detected()))).toList();
        if ((source == null || source.execution() == null) != (activeExecution == null)) {
            throw new IllegalStateException("Current execution observation must match the persisted local execution lifecycle");
        }
        Execution execution = source == null || source.execution() == null ? null
                : execution(source.execution(), activeExecution, printer, capturedAt);
        ConnectionStatus connectionStatus = switch (printer.getConnectionStatus() == null ? "UNKNOWN"
                : printer.getConnectionStatus().toUpperCase(Locale.ROOT)) {
            case "ONLINE" -> ConnectionStatus.ONLINE;
            case "OFFLINE" -> ConnectionStatus.OFFLINE;
            default -> ConnectionStatus.UNKNOWN;
        };
        List<Issue> issues = continuityUnknown
                ? List.of(new Issue(IssueCode.CONTINUITY_UNKNOWN, Severity.WARNING, null)) : List.of();
        return new Observation(UUID.randomUUID(), deviceBootId, observationRevision, descriptorRevision, started, observed,
                source == null ? Completeness.PARTIAL : Completeness.valueOf(source.completeness().name()),
                new Connection(connectionStatus, Boolean.TRUE.equals(printer.getMonitorEnabled()),
                        printer.getLastSeenAt() == null ? null
                                : printer.getLastSeenAt().atZone(ZoneId.systemDefault()).toInstant(),
                        printer.getConsecutiveFailureCount() == null ? 0 : printer.getConsecutiveFailureCount()),
                source == null ? SystemState.UNKNOWN : SystemState.valueOf(source.systemState().name()),
                source == null ? OperationState.UNKNOWN : OperationState.valueOf(source.operationState().name()),
                source == null ? null : source.currentToolId(), heaters, fans, motion, sensors, execution,
                lastExecutionResult(lastFinishedExecution), issues);
    }

    private static LastExecutionResult lastExecutionResult(PrinterExecution execution) {
        if (execution == null || execution.getExecutionUid() == null || execution.getEndedAt() == null
                || "ACTIVE".equalsIgnoreCase(execution.getState())) {
            return null;
        }
        UUID executionId = UUID.fromString(execution.getExecutionUid());
        String state = execution.getState() == null ? "" : execution.getState().trim().toUpperCase(Locale.ROOT);
        String rawState = execution.getLastRawState() == null ? ""
                : execution.getLastRawState().trim().toLowerCase(Locale.ROOT);
        ExecutionOutcome outcome = switch (state) {
            case "COMPLETED" -> ExecutionOutcome.COMPLETED;
            case "CANCELLED", "CANCELED" -> ExecutionOutcome.CANCELLED;
            case "FAILED" -> ExecutionOutcome.FAILED;
            default -> ExecutionOutcome.UNKNOWN;
        };
        ExecutionEvidence evidence = outcome == ExecutionOutcome.UNKNOWN ? ExecutionEvidence.UNCONFIRMED
                : outcome == ExecutionOutcome.COMPLETED && !"complete".equals(rawState)
                ? ExecutionEvidence.RECONCILED : ExecutionEvidence.DEVICE_EXPLICIT;
        ExecutionKind kind = Boolean.TRUE.equals(execution.getHasSeenProcessing())
                ? ExecutionKind.PRINT : ExecutionKind.UNKNOWN;
        String reasonCode = switch (outcome) {
            case COMPLETED -> "complete".equals(rawState) ? "DEVICE_REPORTED_COMPLETE" : "STABLE_IDLE_AFTER_EXECUTION";
            case CANCELLED -> "DEVICE_REPORTED_CANCELLED";
            case FAILED -> "DEVICE_REPORTED_FAILED";
            case UNKNOWN -> "TERMINAL_UNCONFIRMED";
        };
        Instant endedAt = execution.getEndedAt().atZone(ZoneId.systemDefault()).toInstant();
        return new LastExecutionResult(executionId, outcome, kind, evidence, endedAt, endedAt, reasonCode);
    }

    private static Execution execution(PrinterObservation.Execution source, PrinterExecution persisted,
                                       Printer printer, Instant capturedAt) {
        if (persisted.getExecutionUid() == null || persisted.getFirstObservedAt() == null
                || !"ACTIVE".equalsIgnoreCase(persisted.getState())) {
            throw new IllegalStateException("Persisted active execution identity is missing or closed");
        }
        UUID executionId = UUID.fromString(persisted.getExecutionUid());
        String executionSource = persisted.getSource() == null ? "UNKNOWN" : persisted.getSource().toUpperCase(Locale.ROOT);
        ExecutionSource sourceKind = switch (executionSource) {
            case "FARM" -> ExecutionSource.FARM;
            case "EXTERNAL" -> ExecutionSource.EXTERNAL;
            default -> ExecutionSource.UNKNOWN;
        };
        Instant firstObserved = persisted.getFirstObservedAt().atZone(ZoneId.systemDefault()).toInstant();
        Instant started = sourceKind == ExecutionSource.FARM && persisted.getStartedAt() != null
                ? persisted.getStartedAt().atZone(ZoneId.systemDefault()).toInstant() : null;
        String farmJobId = sourceKind == ExecutionSource.FARM && persisted.getFarmJobId() != null
                ? persisted.getFarmJobId().toString() : null;
        BigDecimal fileSize = persisted.getFileSize();
        Long sizeBytes = nonNegativeInteger(fileSize);
        Measurement<Long> filePositionMeasurement = count(null, null, Quality.UNAVAILABLE, MeasurementSource.NONE);
        Measurement<Long> layerUnknown = count(null, null, Quality.UNSUPPORTED, MeasurementSource.NONE);
        Measurement<BigDecimal> printSeconds = requiredMeasurement(source.printSeconds());
        Measurement<BigDecimal> totalSeconds = requiredMeasurement(source.totalSeconds());
        Measurement<BigDecimal> activeSeconds = requiredMeasurement(source.activeSeconds());
        Measurement<BigDecimal> slicerSeconds = requiredMeasurement(source.slicerEstimatedSeconds());
        Measurement<BigDecimal> remainingSeconds = requiredMeasurement(source.remainingSeconds());
        Measurement<BigDecimal> filamentLength = requiredMeasurement(source.filamentLengthMm());
        boolean rrfFileProgress = printer.getFirmwareType() != null
                && printer.getFirmwareType().toUpperCase(Locale.ROOT).contains("RRF")
                && persisted.getFileSize() != null && persisted.getFileSize().signum() > 0;
        String filename = source.filename();
        if (filename != null) {
            int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
            filename = filename.substring(separator + 1);
            filename = filename.replaceAll("[/\\\\:\\p{Cntrl}]", "_");
            if (filename.length() > 128) filename = filename.substring(0, 128);
        }
        ProgressMethod progressMethod = rrfFileProgress ? ProgressMethod.FILE_OFFSET : ProgressMethod.DEVICE_REPORTED;
        Measurement<BigDecimal> progress = measurement(source.progressPercent(), rrfFileProgress
                ? MeasurementSource.FILE_OFFSET : MeasurementSource.DEVICE);
        return new Execution(executionId, sourceKind, source.simulation() ? ExecutionKind.SIMULATION
                : ExecutionKind.PRINT, farmJobId, firstObserved, started,
                new FileSummary(filename, sizeBytes, null), progress, progressMethod,
                filePositionMeasurement,
                new Timing(printSeconds, totalSeconds, activeSeconds,
                        requiredUnavailable(), requiredUnavailable(), slicerSeconds),
                new Layers(layerUnknown, layerUnknown), filamentLength,
                new Remaining(remainingSeconds, RemainingMethod.UNKNOWN, null));
    }

    private static Measurement<Long> count(Long value, Instant at, Quality quality, MeasurementSource source) {
        return new Measurement<>(value, quality, at, source);
    }

    private static Long nonNegativeInteger(BigDecimal value) {
        if (value == null || value.signum() < 0) return null;
        try { return value.longValueExact(); }
        catch (ArithmeticException invalid) { return null; }
    }

    private static Measurement<BigDecimal> measurement(PrinterObservation.Measurement source,
                                                       MeasurementSource measurementSource) {
        if (source == null) return requiredUnavailable();
        Quality quality = switch (source.quality()) {
            case GOOD -> Quality.GOOD;
            case UNAVAILABLE -> Quality.UNAVAILABLE;
            case UNSUPPORTED -> Quality.UNSUPPORTED;
            case INVALID -> Quality.INVALID;
            case NOT_APPLICABLE -> Quality.NOT_APPLICABLE;
        };
        return new Measurement<>(source.value(), quality, source.observedAt(),
                source.value() == null ? MeasurementSource.NONE : measurementSource);
    }

    private static Motion unknownMotion() {
        var unavailable = new Measurement<BigDecimal>(null, Quality.UNSUPPORTED, null, MeasurementSource.NONE);
        return new Motion(CoordinateSpace.UNKNOWN, List.of(), unavailable, unavailable);
    }

    private static Measurement<Boolean> booleanMeasurement(PrinterObservation.BooleanMeasurement source) {
        if (source == null) return null;
        return new Measurement<>(source.value(), Quality.valueOf(source.quality().name()), source.observedAt(),
                source.value() == null ? MeasurementSource.NONE : MeasurementSource.DEVICE);
    }

    private static Measurement<com.fasterxml.jackson.databind.JsonNode> jsonMeasurement(
            PrinterObservation.BooleanMeasurement source) {
        if (source == null) return null;
        var value = source.value() == null ? null
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.booleanNode(source.value());
        return new Measurement<>(value, Quality.valueOf(source.quality().name()), source.observedAt(),
                source.value() == null ? MeasurementSource.NONE : MeasurementSource.DEVICE);
    }

    private static Measurement<BigDecimal> requiredMeasurement(PrinterObservation.Measurement source) {
        return measurement(source, MeasurementSource.DEVICE);
    }

    private static Measurement<BigDecimal> requiredUnavailable() {
        return new Measurement<>(null, Quality.UNAVAILABLE, null, MeasurementSource.NONE);
    }

    private static Measurement<BigDecimal> measurement(PrinterObservation.Measurement source) {
        if (source == null) return null;
        Quality quality = switch (source.quality()) {
            case GOOD -> Quality.GOOD;
            case UNAVAILABLE -> Quality.UNAVAILABLE;
            case UNSUPPORTED -> Quality.UNSUPPORTED;
            case INVALID -> Quality.INVALID;
            case NOT_APPLICABLE -> Quality.NOT_APPLICABLE;
        };
        return new Measurement<>(source.value(), quality, source.observedAt(),
                source.value() == null ? MeasurementSource.NONE : MeasurementSource.DEVICE);
    }
}
