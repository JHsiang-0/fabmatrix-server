package com.example.farm.protocol.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Canonical, firmware-neutral read model; optional schema fields serialize explicitly as null. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CanonicalPrinterModel(int modelVersion, Identity identity, Descriptor descriptor,
                                    Observation observation) {
    public static final int CURRENT_MODEL_VERSION = 2;

    /** Returns the persisted measurement snapshot with the current local connection health overlaid. */
    public CanonicalPrinterModel withConnectionHealth(Connection connection) {
        if (observation == null) return this;
        Observation current = new Observation(observation.id(), observation.deviceBootId(), observation.revision(),
                observation.descriptorRevision(), observation.captureStartedAt(), observation.observedAt(),
                observation.completeness(), connection, observation.systemState(), observation.operationState(),
                observation.currentToolId(), observation.heaters(), observation.fans(), observation.motion(),
                observation.sensors(), observation.currentExecution(), observation.lastExecutionResult(),
                observation.issues());
        return new CanonicalPrinterModel(modelVersion, identity, descriptor, current);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Identity(UUID sourceInstanceId, UUID deviceUid, String localPrinterId) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Descriptor(long revision, String name, String machineNumber, FirmwareFamily firmwareFamily,
                             String firmwareVersion, ConnectionMode connectionMode, MachineMode machineMode,
                             Boolean emulated, String primaryToolId, String primaryBedHeaterId,
                             List<ToolDescriptor> tools, List<HeaterDescriptor> heaters,
                             List<FanDescriptor> fans, List<AxisDescriptor> axes,
                             List<SensorDescriptor> sensors, List<Capability> capabilities,
                             String locationLabel, String configuredMaterial,
                             BigDecimal configuredNozzleDiameterMm) {
        public Descriptor {
            tools = List.copyOf(tools);
            heaters = List.copyOf(heaters);
            fans = List.copyOf(fans);
            axes = List.copyOf(axes);
            sensors = List.copyOf(sensors);
            capabilities = List.copyOf(capabilities);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolDescriptor(String id, String name, List<String> heaterIds,
                                 List<String> fanIds, List<String> extruderIds) {
        public ToolDescriptor {
            heaterIds = List.copyOf(heaterIds);
            fanIds = List.copyOf(fanIds);
            extruderIds = List.copyOf(extruderIds);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HeaterDescriptor(String id, String name, HeaterRole role, RoleEvidence roleEvidence) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FanDescriptor(String id, String name, FanRole role) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AxisDescriptor(String id, String name) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SensorDescriptor(String id, String name, String kind, String unit) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Capability(String code, SupportState support, CapabilityEvidence evidence, Instant detectedAt) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Observation(UUID id, UUID deviceBootId, long revision, long descriptorRevision,
                              Instant captureStartedAt, Instant observedAt, Completeness completeness,
                              Connection connection, SystemState systemState, OperationState operationState,
                              String currentToolId, List<HeaterState> heaters, List<FanState> fans,
                              Motion motion, List<SensorState> sensors, Execution currentExecution,
                              LastExecutionResult lastExecutionResult, List<Issue> issues) {
        public Observation {
            heaters = List.copyOf(heaters);
            fans = List.copyOf(fans);
            sensors = List.copyOf(sensors);
            issues = List.copyOf(issues);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Connection(ConnectionStatus status, boolean monitorEnabled, Instant lastSuccessfulAt,
                            long consecutiveFailures) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Measurement<T>(T value, Quality quality, Instant observedAt, MeasurementSource source) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HeaterState(String id, Measurement<BigDecimal> currentC,
                              Measurement<BigDecimal> effectiveTargetC,
                              Measurement<BigDecimal> activeTargetC,
                              Measurement<BigDecimal> standbyTargetC,
                              Measurement<BigDecimal> pwmRatio, HeaterMode mode) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FanState(String id, Measurement<BigDecimal> pwmRatio, Measurement<BigDecimal> rpm,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Measurement<BigDecimal> requestedPwmRatio) {
        public FanState(String id, Measurement<BigDecimal> pwmRatio, Measurement<BigDecimal> rpm) { this(id, pwmRatio, rpm, null); }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AxisState(String id, Measurement<BigDecimal> positionMm, Measurement<Boolean> homed) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Motion(CoordinateSpace coordinateSpace, List<AxisState> axes,
                         Measurement<BigDecimal> speedFactor, Measurement<BigDecimal> flowFactor) {
        public Motion { axes = List.copyOf(axes); }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SensorState(String id, Measurement<JsonNode> enabled, Measurement<JsonNode> reading) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileSummary(String name, Long sizeBytes, String sha256) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Timing(Measurement<BigDecimal> printSeconds, Measurement<BigDecimal> totalSeconds,
                         Measurement<BigDecimal> activeSeconds, Measurement<BigDecimal> warmupSeconds,
                         Measurement<BigDecimal> pausedSeconds,
                         Measurement<BigDecimal> slicerEstimatedTotalSeconds) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Layers(Measurement<Long> current, Measurement<Long> total) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Remaining(Measurement<BigDecimal> seconds, RemainingMethod method, BigDecimal confidence) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Execution(UUID id, ExecutionSource source, ExecutionKind kind, String localFarmJobId,
                            Instant firstObservedAt, Instant startedAt, FileSummary file,
                            Measurement<BigDecimal> progressPercent, ProgressMethod progressMethod,
                            Measurement<Long> filePositionBytes, Timing timing, Layers layers,
                            Measurement<BigDecimal> filamentLengthMm, Remaining estimatedRemaining) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LastExecutionResult(UUID executionId, ExecutionOutcome outcome, ExecutionKind kind,
                                     ExecutionEvidence evidence, Instant endedAt, Instant observedAt,
                                     String reasonCode) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Issue(IssueCode code, Severity severity, String message) { }

    public enum FirmwareFamily { KLIPPER, RRF, UNKNOWN }
    public enum ConnectionMode { MOONRAKER, RRF_STANDALONE, RRF_SBC, UNKNOWN }
    public enum MachineMode { FFF, UNSUPPORTED, UNKNOWN }
    public enum SupportState { SUPPORTED, UNSUPPORTED, UNKNOWN }
    public enum CapabilityEvidence { PROBED, CONFIGURED, ADAPTER }
    public enum HeaterRole { TOOL, BED, CHAMBER, OTHER, UNKNOWN }
    public enum RoleEvidence { CONFIGURED, PROBED, UNKNOWN }
    public enum FanRole { PART, HOTEND, CHAMBER, OTHER, UNKNOWN }
    public enum Completeness { COMPLETE, PARTIAL, INCONSISTENT }
    public enum ConnectionStatus { ONLINE, OFFLINE, UNKNOWN }
    public enum SystemState { READY, STARTING, FAULT, MAINTENANCE, UNKNOWN }
    public enum OperationState { IDLE, STARTING, PRINTING, PAUSING, PAUSED, RESUMING,
        CANCELLING, BUSY, CHANGING_TOOL, SIMULATING, UNKNOWN }
    public enum Quality { GOOD, UNAVAILABLE, UNSUPPORTED, INVALID, NOT_APPLICABLE }
    public enum MeasurementSource { DEVICE, DEVICE_ESTIMATE, SLICER_ESTIMATE, FILE_OFFSET, SERVER_DERIVED, NONE }
    public enum HeaterMode { OFF, ACTIVE, STANDBY, FAULT, UNKNOWN }
    public enum CoordinateSpace { GCODE, MACHINE, UNKNOWN }
    public enum RemainingMethod { FILE, FILAMENT, SLICER, SIMULATION, SERVER_MODEL, UNKNOWN }
    public enum ExecutionSource { FARM, EXTERNAL, UNKNOWN }
    public enum ExecutionKind { PRINT, SIMULATION, UNKNOWN }
    public enum ProgressMethod { DEVICE_REPORTED, FILE_OFFSET, UNKNOWN }
    public enum ExecutionOutcome { COMPLETED, CANCELLED, FAILED, UNKNOWN }
    public enum ExecutionEvidence { DEVICE_EXPLICIT, RECONCILED, UNCONFIRMED }
    public enum IssueCode { COLLECTION_FAILED, UNKNOWN_RAW_STATE, CONTINUITY_UNKNOWN, TIME_INVALID,
        ROLE_UNCERTAIN, HEATER_FAULT, DEVICE_FAULT, NOT_FFF, VALUE_INVALID }
    public enum Severity { INFO, WARNING, ERROR, CRITICAL }
}
