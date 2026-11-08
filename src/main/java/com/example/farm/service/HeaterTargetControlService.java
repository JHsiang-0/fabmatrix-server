package com.example.farm.service;

import com.example.farm.cloud.CloudControlWire;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.CacheStore;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.entity.Printer;
import com.example.farm.protocol.HeaterControlChannel;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.service.CurrentExecutionPauseService.BeforeAction;
import com.example.farm.service.CurrentExecutionPauseService.Checkpoint;
import com.example.farm.service.CurrentExecutionPauseService.Rejected;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/** Applies a single channel setpoint, never a wait-for-temperature or tool-selection command. */
@Service
@Profile("local")
public class HeaterTargetControlService {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.5");
    private final PrinterService printers;
    private final PrinterExecutionService executions;
    private final CanonicalPrinterStateService models;
    private final PrinterIdentityVerifier identities;
    private final PrinterProtocolAdapterFactory adapters;
    private final CacheStore cache;
    private final boolean monitoring;

    public HeaterTargetControlService(PrinterService printers, PrinterExecutionService executions,
            CanonicalPrinterStateService models, PrinterIdentityVerifier identities, PrinterProtocolAdapterFactory adapters,
            CacheStore cache, @Value("${farm.monitor.enabled:false}") boolean monitoring) {
        this.printers = printers; this.executions = executions; this.models = models; this.identities = identities;
        this.adapters = adapters; this.cache = cache; this.monitoring = monitoring;
    }

    public CloudControlWire.Outcome apply(CloudControlWire.Command command, Checkpoint checkpoint, BeforeAction beforeAction) {
        try {
            checkpoint.check();
            long id = Long.parseLong(command.localPrinterId());
            return PrinterLock.tryExecute(id, cache, () -> locked(id, command, checkpoint, beforeAction), 500);
        } catch (PrinterLock.BusyException busy) { return outcome("REJECTED", "PRINTER_BUSY"); }
        catch (Rejected rejected) { return outcome(rejected.getStatus(), rejected.getCode()); }
        catch (RuntimeException unavailable) { return outcome("REJECTED", "CONTROL_COMMUNICATION_FAILED"); }
    }

    private CloudControlWire.Outcome locked(long id, CloudControlWire.Command command, Checkpoint checkpoint, BeforeAction beforeAction) {
        checkpoint.check();
        Printer printer = context(id, command);
        verifyIdentity(printer);
        var endpoint = new PrinterEndpoint(id, printer.getIpAddress(), printer.getApiKey(), PrinterProtocolType.normalize(printer.getFirmwareType()));
        var adapter = adapters.getAdapter(printer.getFirmwareType());
        var first = adapter.getStatus(endpoint);
        var heater = observation(id, command, first);
        HeaterControlChannel route;
        try { route = adapter.inspectHeater(endpoint, command.heaterId()); }
        catch (IllegalArgumentException | UnsupportedOperationException invalid) { throw reject("HEATER_UNSUPPORTED"); }
        validateRoute(first.observation(), heater, route);
        limits(route, command.targetC());
        compareTarget(heater, command.expectedTargetC());
        checkpoint.check(); context(id, command);
        try { beforeAction.executing(); }
        catch (Exception notAcknowledged) { return outcome("UNKNOWN", "RESULT_UNKNOWN"); }
        try {
            checkpoint.check(); var currentPrinter = context(id, command);
            if (!Objects.equals(printer.getIpAddress(), currentPrinter.getIpAddress())
                    || !Objects.equals(printer.getFirmwareType(), currentPrinter.getFirmwareType())
                    || !Objects.equals(printer.getApiKey(), currentPrinter.getApiKey())) throw reject("IDENTITY_MISMATCH");
            verifyIdentity(currentPrinter);
            var latest = adapter.getStatus(endpoint);
            var currentHeater = observation(id, command, latest);
            var currentRoute = adapter.inspectHeater(endpoint, command.heaterId());
            if (!route.sameRoute(currentRoute)) throw reject("HEATER_UNSUPPORTED");
            validateRoute(latest.observation(), currentHeater, currentRoute);
            limits(currentRoute, command.targetC()); compareTarget(currentHeater, command.expectedTargetC());
            boolean already = currentHeater.effectiveTargetC().value().compareTo(BigDecimal.valueOf(command.targetC())) == 0;
            checkpoint.check(); context(id, command); PrinterLock.ensureValid(id);
            if (!already) adapter.setHeaterTarget(endpoint, currentRoute, command.targetC());
            for (int attempt = 0; attempt < 10; attempt++) {
                context(id, command);
                var observed = adapter.getStatus(endpoint);
                var target = observation(id, command, observed);
                validateRoute(observed.observation(), target, currentRoute);
                if (same(target.effectiveTargetC().value(), BigDecimal.valueOf(command.targetC()))) {
                    return outcome("CONFIRMED", already ? "ALREADY_AT_TARGET" : "HEATER_TARGET_CONFIRMED");
                }
                Thread.sleep(500);
            }
            return outcome("UNKNOWN", "DEVICE_CONFIRMATION_TIMEOUT");
        } catch (Rejected uncertain) { return outcome("UNKNOWN", uncertain.getCode()); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return outcome("UNKNOWN", "RESULT_UNKNOWN");
        } catch (RuntimeException uncertain) { return outcome("UNKNOWN", "HEATER_TARGET_FAILED"); }
    }

    private Printer context(long id, CloudControlWire.Command command) {
        if (!monitoring) throw reject("MONITOR_DISABLED");
        var printer = printers.getById(id);
        if (printer == null || !Objects.equals(printer.getDeviceUid(), command.deviceUid())) throw reject("IDENTITY_MISMATCH");
        if (!Boolean.TRUE.equals(printer.getMonitorEnabled())) throw reject("MONITOR_DISABLED");
        if (!"ONLINE".equals(printer.getConnectionStatus())) throw reject("DEVICE_OFFLINE");
        var model = models.findLatest(id);
        if (model == null || model.observation() == null || model.descriptor() == null
                || !model.identity().sourceInstanceId().toString().equals(command.sourceInstanceId())
                || !model.identity().deviceUid().toString().equals(command.deviceUid())
                || !model.observation().deviceBootId().toString().equals(command.expectedDeviceBootId())
                || model.descriptor().revision() != command.expectedDescriptorRevision()) throw reject("IDENTITY_MISMATCH");
        if (!fresh(model.observation().observedAt()) || model.observation().completeness()
                == CanonicalPrinterModel.Completeness.INCONSISTENT) throw reject("EXECUTION_CONTINUITY_UNKNOWN");
        var execution = executions.findActive(id);
        if (command.expectedExecutionId() == null) {
            if (!"IDLE".equals(command.expectedOperationState()) || execution != null || printer.getCurrentJobId() != null) throw reject("EXECUTION_CHANGED");
        } else {
            if (execution == null || !"ACTIVE".equals(execution.getState())
                    || !command.expectedExecutionId().equals(execution.getExecutionUid())
                    || execution.getLastSeenAt() == null || execution.getLastSeenAt().isBefore(LocalDateTime.now().minusSeconds(30))
                    || !Set.of("FARM", "EXTERNAL").contains(execution.getSource())) throw reject("EXECUTION_CHANGED");
            if ("FARM".equals(execution.getSource()) && (execution.getFarmJobId() == null
                    || !Objects.equals(execution.getFarmJobId(), printer.getCurrentJobId()))) throw reject("EXECUTION_CHANGED");
            if ("EXTERNAL".equals(execution.getSource()) && (execution.getFarmJobId() != null || printer.getCurrentJobId() != null)) throw reject("EXECUTION_CHANGED");
        }
        return printer;
    }

    private PrinterObservation.Heater observation(long id, CloudControlWire.Command command, PrinterDeviceStatus status) {
        var sample = status == null ? null : status.observation();
        if (sample == null || sample.completeness() == PrinterObservation.Completeness.INCONSISTENT
                || !fresh(sample.observedAt()) || sample.systemState() != PrinterObservation.SystemState.READY) throw reject("EXECUTION_CONTINUITY_UNKNOWN");
        if (!sample.operationState().name().equals(command.expectedOperationState())
                || !Set.of("IDLE", "PRINTING", "PAUSED").contains(sample.operationState().name())) throw reject("DEVICE_STATE_CHANGED");
        var execution = executions.findActive(id);
        if (command.expectedExecutionId() != null) {
            if (execution == null || executions.deviceRestarted(execution, status) || status.filename() == null
                    || execution.getObservedFilename() == null || !path(execution.getObservedFilename()).equals(path(status.filename()))
                    || execution.getMaxFilePosition() != null && status.filePosition() != null && status.filePosition().compareTo(execution.getMaxFilePosition()) < 0
                    || execution.getMaxProgress() != null && status.progress() != null && status.progress().compareTo(execution.getMaxProgress()) < 0
                    || execution.getMaxPrintSeconds() != null && status.printDuration() != null && status.printDuration().compareTo(execution.getMaxPrintSeconds()) < 0) throw reject("EXECUTION_CONTINUITY_UNKNOWN");
        } else if (sample.execution() != null) throw reject("EXECUTION_CHANGED");
        var heaters = sample.heaters().stream().filter(value -> value.id().equals(command.heaterId())).toList();
        if (heaters.size() != 1) throw reject("HEATER_UNSUPPORTED");
        var heater = heaters.getFirst();
        if (!Set.of(PrinterObservation.HeaterRole.TOOL, PrinterObservation.HeaterRole.BED).contains(heater.role())
                || heater.mode() == PrinterObservation.HeaterMode.FAULT || heater.effectiveTargetC() == null
                || heater.effectiveTargetC().quality() != PrinterObservation.Quality.GOOD || heater.effectiveTargetC().value() == null
                || !fresh(heater.effectiveTargetC().observedAt())) throw reject("HEATER_UNSUPPORTED");
        if (command.targetC() != 0 && (heater.currentC() == null || heater.currentC().quality() != PrinterObservation.Quality.GOOD
                || heater.currentC().value() == null || !fresh(heater.currentC().observedAt()))) throw reject("HEATER_UNSUPPORTED");
        return heater;
    }

    private void validateRoute(PrinterObservation sample, PrinterObservation.Heater heater, HeaterControlChannel route) {
        if (route == null || !route.id().equals(heater.id()) || route.role() != heater.role()) throw reject("HEATER_UNSUPPORTED");
        var tools = sample.tools().stream().filter(tool -> tool.heaterIds().contains(heater.id())).toList();
        if (heater.role() == PrinterObservation.HeaterRole.TOOL && (tools.size() != 1 || tools.getFirst().heaterIds().size() != 1)) throw reject("HEATER_UNSUPPORTED");
        if (heater.role() == PrinterObservation.HeaterRole.BED && !tools.isEmpty()) throw reject("HEATER_UNSUPPORTED");
        if (route.toolNumber() != null && !tools.getFirst().id().equals("tool-" + route.toolNumber())) throw reject("HEATER_UNSUPPORTED");
    }
    private void limits(HeaterControlChannel route, int target) {
        int maximum = route.role() == PrinterObservation.HeaterRole.TOOL ? 300 : 120;
        if (target < 0 || target > maximum) throw reject("TEMPERATURE_OUT_OF_RANGE");
        if (target == 0) return;
        if (route.minC() == null || route.maxC() == null || route.minC().compareTo(route.maxC()) >= 0
                || route.maxC().signum() <= 0 || route.maxC().compareTo(BigDecimal.valueOf(1000)) > 0) throw reject("HEATER_LIMITS_UNAVAILABLE");
        if (BigDecimal.valueOf(target).compareTo(route.minC()) < 0 || BigDecimal.valueOf(target).compareTo(route.maxC()) > 0) throw reject("TEMPERATURE_OUT_OF_RANGE");
    }
    private void compareTarget(PrinterObservation.Heater heater, BigDecimal expected) {
        if (expected == null || heater.effectiveTargetC().value().compareTo(expected) != 0) throw reject("HEATER_TARGET_CHANGED");
    }
    private void verifyIdentity(Printer printer) {
        try { identities.verifyRemoteFresh(printer); }
        catch (BusinessException invalid) { throw reject("IDENTITY_UNVERIFIED".equals(invalid.getMessage()) ? "IDENTITY_UNVERIFIED" : "IDENTITY_MISMATCH"); }
    }
    private boolean fresh(Instant time) { return time != null && !time.isBefore(Instant.now().minusSeconds(30)) && !time.isAfter(Instant.now().plusSeconds(5)); }
    private boolean same(BigDecimal a, BigDecimal b) { return a != null && b != null && a.subtract(b).abs().compareTo(TOLERANCE) <= 0; }
    private String path(String name) { return name.replace('\\', '/').replaceAll("/+", "/"); }
    private Rejected reject(String code) { return new Rejected("REJECTED", code); }
    private CloudControlWire.Outcome outcome(String status, String code) { return new CloudControlWire.Outcome(status, code); }
}
