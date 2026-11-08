package com.example.farm.service;

import com.example.farm.cloud.CloudControlWire;
import com.example.farm.cloud.CloudRemoteCommandInbox;
import com.example.farm.common.utils.CacheStore;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.protocol.HeaterControlChannel;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.RuntimeControlChannel;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.service.CurrentExecutionPauseService.BeforeAction;
import com.example.farm.service.CurrentExecutionPauseService.Checkpoint;
import com.example.farm.service.CurrentExecutionPauseService.Rejected;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Typed production actions. One durable command, no local login impersonation and no physical replay. */
@Service
@Profile("local")
public class RemoteProductionControlService {
    private final HeaterTargetControlService guard;
    private final PrinterService printers;
    private final PrinterExecutionService executions;
    private final PrintJobService jobs;
    private final PrinterProtocolAdapterFactory adapters;
    private final CacheStore cache;
    private final PlatformTransactionManager transactions;
    private final WebSocketEventPublisher events;
    private final CloudRemoteCommandInbox inbox;

    public RemoteProductionControlService(HeaterTargetControlService guard, PrinterService printers,
            PrinterExecutionService executions, PrintJobService jobs, PrinterProtocolAdapterFactory adapters,
            CacheStore cache, PlatformTransactionManager transactions, WebSocketEventPublisher events,
            CloudRemoteCommandInbox inbox) {
        this.guard = guard; this.printers = printers; this.executions = executions; this.jobs = jobs;
        this.adapters = adapters; this.cache = cache; this.transactions = transactions; this.events = events; this.inbox = inbox;
    }

    /** Also used by schema-3 PAUSE checkpoints, including after acquisition of the device lock. */
    public void validateContext(CloudControlWire.Command command) { guard.context(Long.parseLong(command.localPrinterId()), command); }

    public CloudControlWire.Outcome apply(CloudControlWire.Command command, Checkpoint check, BeforeAction before) {
        try {
            check.check(); long id = Long.parseLong(command.localPrinterId());
            return PrinterLock.tryExecute(id, cache, () -> locked(id, command, check, before), 500);
        } catch (PrinterLock.BusyException busy) { return outcome("REJECTED", "PRINTER_BUSY"); }
        catch (Rejected rejected) { return outcome(rejected.getStatus(), rejected.getCode()); }
        catch (RuntimeException unavailable) { return outcome("REJECTED", "CONTROL_COMMUNICATION_FAILED"); }
    }

    private CloudControlWire.Outcome locked(long id, CloudControlWire.Command command, Checkpoint check, BeforeAction before) {
        check.check(); var printer = guard.context(id, command); guard.verifyIdentity(printer);
        var endpoint = new PrinterEndpoint(id, printer.getIpAddress(), printer.getApiKey(), PrinterProtocolType.normalize(printer.getFirmwareType()));
        var adapter = adapters.getAdapter(printer.getFirmwareType());
        var execution = executions.findActive(id); requireFarmLink(printer, execution);
        var initial = adapter.getStatus(endpoint); sample(id, command, execution, initial, false, false);
        requireActionState(command, initial);
        RuntimeControlChannel route = runtime(command) ? resolve(adapter, endpoint, command) : null;
        if (runtime(command)) requireFlowRoute(command, initial, route);
        List<HeaterControlChannel> heaters = "HEATERS_OFF".equals(command.action()) ? prepareHeaters(command, initial, adapter, endpoint) : List.of();
        if (runtime(command)) compare(value(command, initial), command.expectedPercent());
        check.check(); guard.context(id, command);
        try { before.executing(); }
        catch (Exception unacknowledged) { return outcome("UNKNOWN", "RESULT_UNKNOWN"); }
        try {
            check.check(); var current = guard.context(id, command); requireSameEndpoint(printer, current); guard.verifyIdentity(current);
            var latest = adapter.getStatus(endpoint); sample(id, command, execution, latest, false, false); requireActionState(command, latest);
            if (runtime(command)) {
                var currentRoute = resolve(adapter, endpoint, command);
                if (!Objects.equals(route, currentRoute)) throw reject("TOOL_CHANGED");
                requireFlowRoute(command, latest, currentRoute);
                compare(value(command, latest), command.expectedPercent());
                boolean already = value(command, latest).compareTo(BigDecimal.valueOf(command.targetPercent())) == 0;
                check.check(); guard.context(id, command); PrinterLock.ensureValid(id);
                if (!already) adapter.setRuntimeControl(endpoint, currentRoute, command.targetPercent());
                for (int attempt = 0; attempt < 10; attempt++) {
                    var observed = adapter.getStatus(endpoint);
                    if (sample(id, command, execution, observed, true, false)) {
                        requireActionState(command, observed);
                        requireFlowRoute(command, observed, currentRoute);
                        if (value(command, observed).subtract(BigDecimal.valueOf(command.targetPercent())).abs().compareTo(new BigDecimal("0.5")) <= 0) {
                            if ("SET_FAN_SPEED".equals(command.action()) && !currentRoute.equals(resolve(adapter, endpoint, command))) throw reject("CONTROL_UNSUPPORTED");
                            return outcome("CONFIRMED", already ? "ALREADY_AT_TARGET" : switch (command.action()) {
                                case "SET_FAN_SPEED" -> "FAN_TARGET_CONFIRMED";
                                case "SET_SPEED_FACTOR" -> "SPEED_FACTOR_CONFIRMED";
                                default -> "FLOW_FACTOR_CONFIRMED";
                            });
                        }
                    }
                    pause();
                }
                return outcome("UNKNOWN", "DEVICE_CONFIRMATION_TIMEOUT");
            }
            if ("HEATERS_OFF".equals(command.action())) {
                var currentHeaters = prepareHeaters(command, latest, adapter, endpoint);
                if (!heaters.equals(currentHeaters)) throw reject("HEATER_UNSUPPORTED");
                return heatersOff(id, command, check, printer, endpoint, adapter, currentHeaters);
            }
            if ("RESUME".equals(command.action())) return resume(id, command, check, execution, endpoint, adapter, latest);
            if ("CANCEL".equals(command.action())) return cancel(id, command, check, printer, execution, endpoint, adapter, latest);
            return outcome("UNKNOWN", "UNSUPPORTED_ACTION");
        } catch (Rejected uncertain) { return outcome("UNKNOWN", uncertain.getCode()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return outcome("UNKNOWN", "RESULT_UNKNOWN"); }
        catch (RuntimeException uncertain) { return outcome("UNKNOWN", "ACTION_FAILED"); }
    }

    private CloudControlWire.Outcome resume(long id, CloudControlWire.Command command, Checkpoint check,
            PrinterExecution execution, PrinterEndpoint endpoint, PrinterProtocolAdapter adapter, PrinterDeviceStatus latest) throws InterruptedException {
        String initial = raw(latest);
        boolean already = printing(initial);
        check.check(); guard.context(id, command); PrinterLock.ensureValid(id);
        if ("paused".equals(initial)) adapter.resume(endpoint);
        else if (!already && !"resuming".equals(initial)) throw reject("DEVICE_STATE_CHANGED");
        for (int attempt = 0; attempt < 12; attempt++) {
            var observed = adapter.getStatus(endpoint);
            if (sample(id, command, execution, observed, true, false)) {
                if (printing(raw(observed))) {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        var printer = printers.getById(id); requireFarmLink(printer, execution);
                        Printer update = new Printer(); update.setId(id); update.setStatus("PRINTING"); update.setIsSafeToPrint(false);
                        if (!printers.updateById(update)) throw new IllegalStateException("Resume state not saved");
                        if ("FARM".equals(execution.getSource())) {
                            var job = jobs.getById(execution.getFarmJobId());
                            if (!"PRINTING".equals(job.getStatus())) {
                                PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.PRINTING); job.setStatus("PRINTING");
                                if (!jobs.updateById(job)) throw new IllegalStateException("Resume job not saved");
                                events.publishJobStatus(job);
                            }
                        }
                        executions.observeExecuting(id, execution.getFarmJobId(), execution.getSource(), execution.getExpectedFilename(), observed);
                    });
                    return outcome("CONFIRMED", already ? "ALREADY_RUNNING" : "DEVICE_RESUMED");
                }
                if (!Set.of("paused", "resuming").contains(raw(observed))) throw reject("DEVICE_STATE_CHANGED");
            }
            pause();
        }
        return outcome("UNKNOWN", "DEVICE_CONFIRMATION_TIMEOUT");
    }

    private CloudControlWire.Outcome cancel(long id, CloudControlWire.Command command, Checkpoint check, Printer printer,
            PrinterExecution execution, PrinterEndpoint endpoint, PrinterProtocolAdapter adapter, PrinterDeviceStatus latest) throws InterruptedException {
        if (endpoint.protocolType() == PrinterProtocolType.RRF) {
            if (printing(raw(latest))) { check.check(); guard.context(id, command); adapter.pause(endpoint); }
            boolean paused = "paused".equals(raw(latest));
            for (int attempt = 0; !paused && attempt < 12; attempt++) {
                var observed = adapter.getStatus(endpoint);
                if (sample(id, command, execution, observed, true, false)) {
                    paused = "paused".equals(raw(observed));
                    if (!paused && !"pausing".equals(raw(observed)) && !printing(raw(observed))) throw reject("DEVICE_STATE_CHANGED");
                }
                if (!paused) pause();
            }
            if (!paused) return outcome("UNKNOWN", "DEVICE_CONFIRMATION_TIMEOUT");
        }
        // RRF's final M0 is separate from M25: policy/TTL/identity must still allow this physical stage.
        check.check(); requireSameEndpoint(printer, guard.context(id, command)); guard.verifyIdentity(printer); PrinterLock.ensureValid(id);
        var ready = adapter.getStatus(endpoint); sample(id, command, execution, ready, false, false);
        if (endpoint.protocolType() == PrinterProtocolType.RRF ? !"paused".equals(raw(ready))
                : !printing(raw(ready)) && !"paused".equals(raw(ready))) throw reject("DEVICE_STATE_CHANGED");
        check.check(); guard.context(id, command); PrinterLock.ensureValid(id);
        adapter.requestCancel(endpoint);
        int stableIdle = 0;
        for (int attempt = 0; attempt < 12; attempt++) {
            var observed = adapter.getStatus(endpoint);
            if (!sample(id, command, execution, observed, true, true)) { stableIdle = 0; pause(); continue; }
            boolean terminal;
            if (endpoint.protocolType() == PrinterProtocolType.KLIPPER) terminal = "cancelled".equals(raw(observed));
            else {
                var evidence = observed.observation().terminalEvidence();
                terminal = "idle".equals(raw(observed)) && evidence != null && !evidence.currentFilePresent()
                        && evidence.lastFilename() != null && path(evidence.lastFilename()).equals(path(execution.getObservedFilename()))
                        && evidence.lastDuration() != null && (execution.getMaxPrintSeconds() == null
                            || evidence.lastDuration().compareTo(execution.getMaxPrintSeconds()) >= 0);
            }
            if (terminal) stableIdle++; else stableIdle = 0;
            if (stableIdle >= (endpoint.protocolType() == PrinterProtocolType.RRF ? 2 : 1)) {
                finishCancelled(id, execution, observed, endpoint.protocolType());
                return outcome("CONFIRMED", "DEVICE_CANCELLED");
            }
            if (!terminal && !Set.of("processing", "printing", "paused", "pausing", "cancelling", "idle").contains(raw(observed))) throw reject("DEVICE_STATE_CHANGED");
            pause();
        }
        return outcome("UNKNOWN", "DEVICE_CONFIRMATION_TIMEOUT");
    }

    private void finishCancelled(long id, PrinterExecution execution, PrinterDeviceStatus observed, PrinterProtocolType protocol) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var printer = printers.getById(id); requireFarmLink(printer, execution);
            // Preserve evidence honestly: standalone RRF confirms a command-correlated stop, not a DWC cancellation flag.
            execution.setLastRawState(protocol == PrinterProtocolType.RRF ? "remote_cancel_confirmed" : "cancelled");
            if ("FARM".equals(execution.getSource())) {
                var job = jobs.getById(execution.getFarmJobId());
                if (!executions.finishFarmExecution(execution, printer, job, PrintJobStatus.CANCELLED, "远程取消后已确认设备停止")) throw new IllegalStateException("Cancel binding changed");
                events.publishJobStatus(job);
            } else {
                if (!executions.finishNonFarmExecution(execution, "CANCELLED", "远程取消后已确认设备停止")) throw new IllegalStateException("Execution changed");
                Printer update = new Printer(); update.setId(id); update.setStatus("IDLE"); update.setIsSafeToPrint(false);
                if (!printers.updateById(update)) throw new IllegalStateException("Cancel state not saved");
            }
        });
    }

    private CloudControlWire.Outcome heatersOff(long id, CloudControlWire.Command command, Checkpoint check, Printer printer,
            PrinterEndpoint endpoint, PrinterProtocolAdapter adapter, List<HeaterControlChannel> routes) throws InterruptedException {
        int applied = 0; boolean changed = false;
        try {
            for (var route : routes) {
                check.check(); requireSameEndpoint(printer, guard.context(id, command)); guard.verifyIdentity(printer);
                var latest = adapter.getStatus(endpoint); sample(id, command, null, latest, false, false);
                var expected = command.expectedHeaterTargets().stream().filter(value -> value.heaterId().equals(route.id())).findFirst().orElseThrow();
                var heater = heater(latest, route.id()); compare(heater.effectiveTargetC().value(), expected.expectedTargetC());
                if (!route.sameRoute(adapter.inspectHeater(endpoint, route.id()))) throw reject("HEATER_UNSUPPORTED");
                boolean already = off(heater, endpoint.protocolType());
                check.check(); PrinterLock.ensureValid(id);
                if (!already) { changed = true; adapter.setHeaterTarget(endpoint, route, 0); }
                boolean confirmed = false;
                for (int attempt = 0; attempt < 10; attempt++) {
                    var observed = adapter.getStatus(endpoint);
                    if (sample(id, command, null, observed, true, false) && off(heater(observed, route.id()), endpoint.protocolType())) { confirmed = true; break; }
                    pause();
                }
                if (!confirmed) return outcome("UNKNOWN", applied > 0 ? "PARTIAL_APPLIED" : "DEVICE_CONFIRMATION_TIMEOUT");
                inbox.channelApplied(command, route.id()); applied++;
            }
            // Another local console may have reheated an earlier channel while a later one was being closed.
            // Only one complete fresh frame can prove the aggregate result; never send any additional writes here.
            for (int attempt = 0; attempt < 10; attempt++) {
                var observed = adapter.getStatus(endpoint);
                if (sample(id, command, null, observed, true, false)) {
                    var actualIds = observed.observation().heaters().stream().map(PrinterObservation.Heater::id).sorted().toList();
                    var expectedIds = routes.stream().map(HeaterControlChannel::id).sorted().toList();
                    if (!actualIds.equals(expectedIds) || routes.stream().anyMatch(route -> !off(heater(observed, route.id()), endpoint.protocolType()))) {
                        return outcome("UNKNOWN", "PARTIAL_APPLIED");
                    }
                    return outcome("CONFIRMED", changed ? "HEATERS_OFF_CONFIRMED" : "ALREADY_AT_TARGET");
                }
                pause();
            }
            return outcome("UNKNOWN", "PARTIAL_APPLIED");
        } catch (RuntimeException failed) { return outcome("UNKNOWN", applied > 0 ? "PARTIAL_APPLIED" : "ACTION_FAILED"); }
    }

    private List<HeaterControlChannel> prepareHeaters(CloudControlWire.Command command, PrinterDeviceStatus status,
            PrinterProtocolAdapter adapter, PrinterEndpoint endpoint) {
        var sample = status.observation();
        if (sample.operationState() != PrinterObservation.OperationState.IDLE || sample.heaters().isEmpty() || sample.heaters().size() > 16
                || command.expectedHeaterTargets() == null || command.expectedHeaterTargets().size() != sample.heaters().size()) throw reject("HEATER_UNSUPPORTED");
        var routes = new ArrayList<HeaterControlChannel>();
        for (var value : sample.heaters().stream().sorted(Comparator.comparing(PrinterObservation.Heater::id)).toList()) {
            var heater = heater(status, value.id());
            var matches = command.expectedHeaterTargets().stream().filter(expected -> expected.heaterId().equals(value.id())).toList();
            if (matches.size() != 1) throw reject("HEATER_UNSUPPORTED");
            compare(heater.effectiveTargetC().value(), matches.getFirst().expectedTargetC());
            var route = adapter.inspectHeater(endpoint, value.id()); guard.validateRoute(sample, heater, route); routes.add(route);
        }
        return routes;
    }

    private PrinterObservation.Heater heater(PrinterDeviceStatus status, String id) {
        var matches = status.observation().heaters().stream().filter(value -> value.id().equals(id)).toList();
        if (matches.size() != 1) throw reject("HEATER_UNSUPPORTED");
        var heater = matches.getFirst();
        if (!Set.of(PrinterObservation.HeaterRole.TOOL, PrinterObservation.HeaterRole.BED).contains(heater.role())
                || heater.mode() == PrinterObservation.HeaterMode.FAULT) throw reject("HEATER_UNSUPPORTED");
        measurement(heater.effectiveTargetC()); return heater;
    }
    private boolean off(PrinterObservation.Heater heater, PrinterProtocolType protocol) {
        return heater.effectiveTargetC().value().signum() == 0 && (protocol != PrinterProtocolType.RRF || heater.mode() == PrinterObservation.HeaterMode.OFF);
    }

    /** An inconsistent transition frame is never success; after a write it may only consume a bounded read attempt. */
    private boolean sample(long id, CloudControlWire.Command command, PrinterExecution execution, PrinterDeviceStatus status,
            boolean allowInconsistent, boolean allowTerminal) {
        guard.context(id, command);
        var current = executions.findActive(id);
        if (execution != null && (current == null || !Objects.equals(execution.getExecutionUid(), current.getExecutionUid())
                || executions.deviceRestarted(execution, status))) throw reject("EXECUTION_CHANGED");
        var sample = status == null ? null : status.observation();
        if (sample == null || !fresh(sample.observedAt())) throw reject("EXECUTION_CONTINUITY_UNKNOWN");
        boolean terminal = allowTerminal && Set.of("idle", "cancelled").contains(raw(status));
        if (execution != null && !terminal && (status.filename() == null || execution.getObservedFilename() == null
                || !path(execution.getObservedFilename()).equals(path(status.filename()))
                || command.expectedFileSizeBytes() != null && (status.fileSize() == null || status.fileSize().compareTo(BigDecimal.valueOf(command.expectedFileSizeBytes())) != 0)
                || command.expectedFilePositionBytes() != null && (status.filePosition() == null || status.filePosition().compareTo(BigDecimal.valueOf(command.expectedFilePositionBytes())) < 0)
                || execution.getMaxFilePosition() != null && status.filePosition() != null && status.filePosition().compareTo(execution.getMaxFilePosition()) < 0
                || execution.getMaxProgress() != null && status.progress() != null && status.progress().compareTo(execution.getMaxProgress()) < 0
                || execution.getMaxPrintSeconds() != null && status.printDuration() != null && status.printDuration().compareTo(execution.getMaxPrintSeconds()) < 0)) throw reject("EXECUTION_CONTINUITY_UNKNOWN");
        if (execution != null && terminal && status.filename() != null
                && !path(execution.getObservedFilename()).equals(path(status.filename()))) throw reject("EXECUTION_CHANGED");
        if (sample.completeness() == PrinterObservation.Completeness.INCONSISTENT) {
            if (allowInconsistent) return false;
            throw reject("EXECUTION_CONTINUITY_UNKNOWN");
        }
        if (sample.systemState() != PrinterObservation.SystemState.READY) throw reject("DEVICE_STATE_CHANGED");
        if (execution == null && (sample.operationState() != PrinterObservation.OperationState.IDLE || sample.execution() != null)) throw reject("EXECUTION_CHANGED");
        return true;
    }

    private void requireActionState(CloudControlWire.Command command, PrinterDeviceStatus status) {
        String state = raw(status);
        if ("RESUME".equals(command.action())) {
            if (!Set.of("paused", "resuming", "printing", "processing").contains(state)) throw reject("DEVICE_STATE_CHANGED");
        } else if ("CANCEL".equals(command.action())) {
            if (!Set.of("printing", "processing", "paused", "pausing").contains(state)) throw reject("DEVICE_STATE_CHANGED");
        } else if (!status.observation().operationState().name().equals(command.expectedOperationState())
                || !Set.of("IDLE", "PRINTING", "PAUSED").contains(command.expectedOperationState())) throw reject("DEVICE_STATE_CHANGED");
        if (Set.of("RESUME", "CANCEL", "SET_SPEED_FACTOR", "SET_FLOW_FACTOR").contains(command.action()) && command.expectedExecutionId() == null) throw reject("EXECUTION_CHANGED");
    }
    private BigDecimal value(CloudControlWire.Command command, PrinterDeviceStatus status) {
        var sample = status.observation();
        if ("SET_FAN_SPEED".equals(command.action())) {
            var fans = sample.fans().stream().filter(fan -> fan.id().equals(command.fanId())).toList();
            if (fans.size() != 1 || fans.getFirst().role() != PrinterObservation.FanRole.PART) throw reject("CONTROL_UNSUPPORTED");
            return measurement(fans.getFirst().requestedPwmRatio()).multiply(BigDecimal.valueOf(100));
        }
        if (sample.motion() == null) throw reject("CONTROL_UNSUPPORTED");
        if ("SET_FLOW_FACTOR".equals(command.action())) {
            if (!Objects.equals(sample.currentToolId(), command.toolId())) throw reject("TOOL_CHANGED");
            var tools = sample.tools().stream().filter(tool -> tool.id().equals(command.toolId())).toList();
            if (tools.size() != 1 || tools.getFirst().extruderIds().size() != 1) throw reject("CONTROL_UNSUPPORTED");
            var extruder = tools.getFirst().extruderIds().getFirst();
            if (sample.tools().stream().filter(tool -> tool.extruderIds().contains(extruder)).count() != 1) throw reject("CONTROL_UNSUPPORTED");
            return measurement(sample.motion().flowFactor()).multiply(BigDecimal.valueOf(100));
        }
        return measurement(sample.motion().speedFactor()).multiply(BigDecimal.valueOf(100));
    }
    private RuntimeControlChannel resolve(PrinterProtocolAdapter adapter, PrinterEndpoint endpoint, CloudControlWire.Command command) {
        try {
            var channel = adapter.inspectRuntimeControl(endpoint, command.action(), command.fanId(), command.toolId());
            if (channel == null || !command.action().equals(channel.action())) throw reject("CONTROL_UNSUPPORTED");
            return channel;
        }
        catch (IllegalArgumentException | UnsupportedOperationException unsupported) { throw reject("CONTROL_UNSUPPORTED"); }
    }
    private void requireFlowRoute(CloudControlWire.Command command, PrinterDeviceStatus status, RuntimeControlChannel route) {
        if (!"SET_FLOW_FACTOR".equals(command.action())) return;
        var selected = status.observation().tools().stream().filter(tool -> tool.id().equals(command.toolId())).toList();
        if (selected.size() != 1 || selected.getFirst().extruderIds().size() != 1 || !Objects.equals(route.toolId(), command.toolId())
                || !selected.getFirst().extruderIds().getFirst().equals(route.id())) throw reject("TOOL_CHANGED");
    }
    private void requireFarmLink(Printer printer, PrinterExecution execution) {
        if (execution != null && "FARM".equals(execution.getSource())) {
            var job = jobs.getById(execution.getFarmJobId());
            if (job == null || !Objects.equals(job.getPrinterId(), printer.getId()) || !Objects.equals(printer.getCurrentJobId(), job.getId())
                    || !Set.of("PRINTING", "PAUSED").contains(job.getStatus())) throw reject("EXECUTION_CHANGED");
        }
    }
    private BigDecimal measurement(PrinterObservation.Measurement value) {
        if (value == null || value.quality() != PrinterObservation.Quality.GOOD || value.value() == null || !fresh(value.observedAt())) throw reject("CONTROL_UNSUPPORTED");
        return value.value();
    }
    private void compare(BigDecimal current, BigDecimal expected) { if (expected == null || current.compareTo(expected) != 0) throw reject("CONTROL_VALUE_CHANGED"); }
    private void requireSameEndpoint(Printer first, Printer latest) {
        if (!Objects.equals(first.getIpAddress(), latest.getIpAddress()) || !Objects.equals(first.getFirmwareType(), latest.getFirmwareType())
                || !Objects.equals(first.getApiKey(), latest.getApiKey())) throw reject("IDENTITY_MISMATCH");
    }
    private boolean runtime(CloudControlWire.Command command) { return Set.of("SET_FAN_SPEED", "SET_SPEED_FACTOR", "SET_FLOW_FACTOR").contains(command.action()); }
    private boolean fresh(Instant at) { return at != null && !at.isBefore(Instant.now().minusSeconds(30)) && !at.isAfter(Instant.now().plusSeconds(5)); }
    private String raw(PrinterDeviceStatus status) { return status.rawState() == null ? "" : status.rawState().trim().toLowerCase(java.util.Locale.ROOT); }
    private boolean printing(String state) { return "printing".equals(state) || "processing".equals(state); }
    private String path(String value) { return value.replace('\\', '/').replaceAll("/+", "/"); }
    private void pause() throws InterruptedException { Thread.sleep(250); }
    private Rejected reject(String code) { return new Rejected("REJECTED", code); }
    private CloudControlWire.Outcome outcome(String status, String code) { return new CloudControlWire.Outcome(status, code); }
}
