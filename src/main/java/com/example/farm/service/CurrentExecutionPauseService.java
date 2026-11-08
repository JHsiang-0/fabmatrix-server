package com.example.farm.service;

import com.example.farm.cloud.CloudControlWire;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.CacheStore;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.protocol.observation.PrinterObservation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Locale;

/** Shared execution-level pause, independent of a local login or a fabricated FarmJob. */
@Service
@Profile("local")
public class CurrentExecutionPauseService {
    @FunctionalInterface public interface Checkpoint { void check(); }
    @FunctionalInterface public interface BeforeAction { void executing() throws Exception; }
    private final PrinterService printers;
    private final PrinterExecutionService executions;
    private final PrintJobService jobs;
    private final PrinterProtocolAdapterFactory adapters;
    private final PrinterIdentityVerifier identities;
    private final CacheStore cache;
    private final WebSocketEventPublisher events;
    private final PlatformTransactionManager transactions;
    private final boolean monitoring;

    public CurrentExecutionPauseService(PrinterService printers, PrinterExecutionService executions,
            PrintJobService jobs, PrinterProtocolAdapterFactory adapters, PrinterIdentityVerifier identities,
            CacheStore cache, WebSocketEventPublisher events, PlatformTransactionManager transactions,
            @Value("${farm.monitor.enabled:false}") boolean monitoring) {
        this.printers = printers; this.executions = executions; this.jobs = jobs;
        this.adapters = adapters; this.identities = identities; this.cache = cache;
        this.events = events; this.transactions = transactions; this.monitoring = monitoring;
    }

    public CloudControlWire.Outcome pause(CloudControlWire.Command command, Checkpoint checkpoint,
                                         BeforeAction beforeAction) {
        try {
            checkpoint.check();
            long id = Long.parseLong(command.localPrinterId());
            if (id < 1) return rejected("IDENTITY_MISMATCH");
            return PrinterLock.tryExecute(id, cache, () -> lockedPause(id, command, checkpoint, beforeAction), 500);
        } catch (PrinterLock.BusyException busy) { return rejected("PRINTER_BUSY"); }
        catch (Rejected rejected) { return new CloudControlWire.Outcome(rejected.status, rejected.code); }
        catch (NumberFormatException invalid) { return rejected("IDENTITY_MISMATCH"); }
        catch (RuntimeException unavailable) { return rejected("CONTROL_COMMUNICATION_FAILED"); }
    }

    private CloudControlWire.Outcome lockedPause(long id, CloudControlWire.Command command,
            Checkpoint checkpoint, BeforeAction beforeAction) {
        checkpoint.check();
        if (!monitoring) return rejected("MONITOR_DISABLED");
        Printer printer = printers.getById(id);
        if (printer == null || !Objects.equals(printer.getDeviceUid(), command.deviceUid())) {
            return rejected("IDENTITY_MISMATCH");
        }
        if (!Boolean.TRUE.equals(printer.getMonitorEnabled())) return rejected("MONITOR_DISABLED");
        if (!"ONLINE".equals(printer.getConnectionStatus())) return rejected("DEVICE_OFFLINE");
        PrinterExecution execution = executions.findActive(id);
        requireExecution(execution, command);
        if (execution.getLastSeenAt() == null || execution.getLastSeenAt().isBefore(LocalDateTime.now().minusSeconds(30))
                || execution.getLastSeenAt().isAfter(LocalDateTime.now().plusSeconds(5))) {
            return rejected("EXECUTION_CONTINUITY_UNKNOWN");
        }
        if (!"FARM".equals(execution.getSource()) && !"EXTERNAL".equals(execution.getSource())) {
            return rejected("EXECUTION_CONTINUITY_UNKNOWN");
        }
        if ("FARM".equals(execution.getSource())) {
            if (!Objects.equals(printer.getCurrentJobId(), execution.getFarmJobId()) || execution.getFarmJobId() == null) {
                return rejected("EXECUTION_CHANGED");
            }
            var job = jobs.getById(execution.getFarmJobId());
            if (job == null || !Objects.equals(job.getPrinterId(), id)
                    || !java.util.Set.of("PRINTING", "PAUSED").contains(job.getStatus())) {
                return rejected("EXECUTION_CHANGED");
            }
        } else if (execution.getFarmJobId() != null || printer.getCurrentJobId() != null) {
            return rejected("EXECUTION_CONTINUITY_UNKNOWN");
        }
        try { identities.verifyRemoteFresh(printer); }
        catch (BusinessException unverified) {
            return rejected("IDENTITY_UNVERIFIED".equals(unverified.getMessage()) ? "IDENTITY_UNVERIFIED" : "IDENTITY_MISMATCH");
        }
        var endpoint = new PrinterEndpoint(id, printer.getIpAddress(), printer.getApiKey(),
                PrinterProtocolType.normalize(printer.getFirmwareType()));
        var adapter = adapters.getAdapter(printer.getFirmwareType());
        PrinterDeviceStatus before = adapter.getStatus(endpoint);
        requireContinuity(execution, before, command);
        PausePhase beforePhase = phase(before);
        if (beforePhase == PausePhase.OTHER) return rejected("DEVICE_STATE_CHANGED");
        checkpoint.check();
        requireExecution(executions.findActive(id), command);
        try { beforeAction.executing(); }
        catch (Exception notAcknowledged) { return unknown("RESULT_UNKNOWN"); }
        try {
            checkpoint.check();
            requireExecution(executions.findActive(id), command);
            PrinterLock.ensureValid(id);
        } catch (RuntimeException noLongerAllowed) { return unknown("RESULT_UNKNOWN"); }
        try {
            PrinterDeviceStatus latest = adapter.getStatus(endpoint);
            requireContinuity(execution, latest, command);
            PausePhase latestPhase = phase(latest);
            if (latestPhase == PausePhase.OTHER) return unknown("DEVICE_STATE_CHANGED");
            checkpoint.check();
            boolean alreadyPaused = latestPhase == PausePhase.PAUSED;
            if (latestPhase == PausePhase.PRINTING) {
                if (beforePhase != PausePhase.PRINTING) return unknown("DEVICE_STATE_CHANGED");
                adapter.pause(endpoint);
            }
            boolean inconsistentRead = false;
            for (int attempt = 0; attempt < 10; attempt++) {
                PrinterDeviceStatus observed = adapter.getStatus(endpoint);
                requireExecution(executions.findActive(id), command);
                if (observed != null && observed.observation() != null
                        && observed.observation().completeness() == PrinterObservation.Completeness.INCONSISTENT) {
                    requireContinuity(execution, observed, command, true);
                    inconsistentRead = true; Thread.sleep(500); continue;
                }
                requireContinuity(execution, observed, command);
                PausePhase observedPhase = phase(observed);
                if (observedPhase == PausePhase.PAUSED) {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        Printer update = new Printer(); update.setId(id); update.setStatus("PAUSED");
                        update.setIsSafeToPrint(false);
                        if (!printers.updateById(update)) throw new IllegalStateException("Pause state not saved");
                        if ("FARM".equals(execution.getSource())) {
                            var job = jobs.getById(execution.getFarmJobId());
                            if (job == null || !Objects.equals(job.getPrinterId(), id)) throw new IllegalStateException("Job changed");
                            if (!"PAUSED".equals(job.getStatus())) {
                                PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.PAUSED);
                                job.setStatus("PAUSED");
                                if (!jobs.updateById(job)) throw new IllegalStateException("Job state not saved");
                                events.publishJobStatus(job);
                            }
                        }
                        executions.observeExecuting(id, execution.getFarmJobId(), execution.getSource(),
                                execution.getExpectedFilename(), observed);
                    });
                    return new CloudControlWire.Outcome("CONFIRMED", alreadyPaused ? "ALREADY_PAUSED" : "DEVICE_PAUSED");
                }
                if (observedPhase == PausePhase.OTHER) return unknown("DEVICE_STATE_CHANGED");
                Thread.sleep(500);
            }
            return unknown(inconsistentRead ? "EXECUTION_CONTINUITY_UNKNOWN" : "DEVICE_CONFIRMATION_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unknown("RESULT_UNKNOWN");
        } catch (Rejected uncertain) {
            return unknown(uncertain.getCode());
        } catch (RuntimeException uncertain) { return unknown("RESULT_UNKNOWN"); }
    }

    private enum PausePhase { PRINTING, PAUSING, PAUSED, OTHER }

    /** Legacy PAUSED includes RRF pausing; only a final, fresh protocol state proves completion. */
    private PausePhase phase(PrinterDeviceStatus status) {
        String raw = status.rawState() == null ? "" : status.rawState().trim().toLowerCase(Locale.ROOT);
        if ("pausing".equals(raw)) return PausePhase.PAUSING;
        if ("paused".equals(raw) && status.status() == PrinterStatus.PAUSED) return PausePhase.PAUSED;
        if (("printing".equals(raw) || "processing".equals(raw)) && status.status() == PrinterStatus.PRINTING) {
            return PausePhase.PRINTING;
        }
        if ((raw.isEmpty() || "unknown".equals(raw)) && status.observation() != null) {
            PrinterObservation.OperationState operation = status.observation().operationState();
            if (operation == PrinterObservation.OperationState.PAUSED && status.status() == PrinterStatus.PAUSED) return PausePhase.PAUSED;
            if (operation == PrinterObservation.OperationState.PAUSING) return PausePhase.PAUSING;
        }
        return PausePhase.OTHER;
    }

    private void requireExecution(PrinterExecution execution, CloudControlWire.Command command) {
        if (execution == null || !"ACTIVE".equals(execution.getState())
                || !Objects.equals(execution.getExecutionUid(), command.expectedExecutionId())) {
            throw new Rejected("REJECTED", "EXECUTION_CHANGED");
        }
    }
    private void requireContinuity(PrinterExecution execution, PrinterDeviceStatus current, CloudControlWire.Command command) {
        requireContinuity(execution, current, command, false);
    }
    private void requireContinuity(PrinterExecution execution, PrinterDeviceStatus current, CloudControlWire.Command command, boolean allowInconsistent) {
        if (current == null || !allowInconsistent && current.observation() != null
                && current.observation().completeness() == PrinterObservation.Completeness.INCONSISTENT
                || executions.deviceRestarted(execution, current)
                || command.expectedFileName() == null || current.filename() == null
                || !name(command.expectedFileName()).equals(name(current.filename()))
                || execution.getObservedFilename() == null
                || !path(execution.getObservedFilename()).equals(path(current.filename()))
                || command.expectedFileSizeBytes() != null && (current.fileSize() == null
                    || current.fileSize().compareTo(java.math.BigDecimal.valueOf(command.expectedFileSizeBytes())) != 0)
                || command.expectedFilePositionBytes() != null && (current.filePosition() == null
                    || current.filePosition().compareTo(java.math.BigDecimal.valueOf(command.expectedFilePositionBytes())) < 0)
                || execution.getMaxFilePosition() != null && current.filePosition() != null
                    && current.filePosition().compareTo(execution.getMaxFilePosition()) < 0
                || execution.getMaxProgress() != null && current.progress() != null
                    && current.progress().compareTo(execution.getMaxProgress()) < 0
                || execution.getMaxPrintSeconds() != null && current.printDuration() != null
                    && current.printDuration().compareTo(execution.getMaxPrintSeconds()) < 0) {
            throw new Rejected("REJECTED", "EXECUTION_CONTINUITY_UNKNOWN");
        }
    }
    private String name(String input) {
        String normalized = input.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[/\\\\:\\p{Cntrl}]", "_");
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }
    private String path(String input) { return input.replace('\\', '/').replaceAll("/+", "/"); }
    public static class Rejected extends RuntimeException {
        final String status, code;
        public Rejected(String status, String code) { super(code); this.status = status; this.code = code; }
        public String getStatus() { return status; }
        public String getCode() { return code; }
    }
    private CloudControlWire.Outcome rejected(String code) { return new CloudControlWire.Outcome("REJECTED", code); }
    private CloudControlWire.Outcome unknown(String code) { return new CloudControlWire.Outcome("UNKNOWN", code); }
}
