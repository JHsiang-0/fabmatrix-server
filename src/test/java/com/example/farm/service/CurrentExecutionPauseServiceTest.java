package com.example.farm.service;

import com.example.farm.cloud.CloudControlWire;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.InMemoryCache;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Only protocol mocks; these tests never address a physical printer. */
class CurrentExecutionPauseServiceTest {
    private final PrinterService printers = mock(PrinterService.class);
    private final PrinterExecutionService executions = mock(PrinterExecutionService.class);
    private final PrintJobService jobs = mock(PrintJobService.class);
    private final PrinterIdentityVerifier identities = mock(PrinterIdentityVerifier.class);
    private final WebSocketEventPublisher events = mock(WebSocketEventPublisher.class);
    private final PrinterProtocolAdapter adapter = mock(PrinterProtocolAdapter.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final InMemoryCache cache = new InMemoryCache();
    private CloudControlWire.Command command;
    private Printer printer;
    private PrinterExecution execution;

    @BeforeEach void setup() {
        long now = System.currentTimeMillis();
        command = new CloudControlWire.Command(1, uuid(), "PAUSE", uuid(), 1, uuid(), 1, uuid(), uuid(), "9321",
                uuid(), "part.gcode", 10000L, 100L, 2, uuid(), "cloud-operator", now, now + 15000);
        printer = new Printer(); printer.setId(9321L); printer.setDeviceUid(command.deviceUid());
        printer.setIpAddress("127.0.0.10"); printer.setFirmwareType("KLIPPER");
        printer.setConnectionStatus("ONLINE"); printer.setMonitorEnabled(true);
        execution = new PrinterExecution(); execution.setId(1L); execution.setPrinterId(9321L);
        execution.setExecutionUid(command.expectedExecutionId()); execution.setState("ACTIVE");
        execution.setSource("EXTERNAL"); execution.setObservedFilename("part.gcode");
        execution.setLastSeenAt(LocalDateTime.now());
        when(printers.getById(9321L)).thenReturn(printer);
        when(executions.findActive(9321L)).thenReturn(execution);
        when(printers.updateById(any(Printer.class))).thenReturn(true);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @ParameterizedTest @EnumSource(value = PrinterProtocolType.class, names = {"KLIPPER", "RRF"})
    void confirmsExternalPauseOnlyAfterDeviceReadbackWithoutCreatingFarmJob(PrinterProtocolType protocol) {
        printer.setFirmwareType(protocol.name()); when(adapter.protocolType()).thenReturn(protocol);
        when(adapter.getStatus(any())).thenReturn(status(PrinterStatus.PRINTING), status(PrinterStatus.PRINTING), status(PrinterStatus.PAUSED));
        var accepted = new AtomicBoolean();
        var outcome = service(true).pause(command, () -> { }, () -> accepted.set(true));
        assertThat(outcome).isEqualTo(new CloudControlWire.Outcome("CONFIRMED", "DEVICE_PAUSED"));
        assertThat(accepted).isTrue();
        verify(adapter).pause(any(PrinterEndpoint.class));
        verify(executions).observeExecuting(eq(9321L), eq(null), eq("EXTERNAL"), eq(null), any());
        verifyNoInteractions(jobs);
    }

    @Test void farmExecutionUpdatesOnlyTheLocallyBoundJob() {
        execution.setSource("FARM"); execution.setFarmJobId(44L); printer.setCurrentJobId(44L);
        var job = new PrintJob(); job.setId(44L); job.setPrinterId(9321L); job.setStatus("PRINTING");
        when(jobs.getById(44L)).thenReturn(job); when(jobs.updateById(job)).thenReturn(true);
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        when(adapter.getStatus(any())).thenReturn(status(PrinterStatus.PRINTING), status(PrinterStatus.PRINTING), status(PrinterStatus.PAUSED));
        assertThat(service(true).pause(command, () -> { }, () -> { }).status()).isEqualTo("CONFIRMED");
        assertThat(job.getStatus()).isEqualTo("PAUSED"); verify(events).publishJobStatus(job);
    }

    @Test void alreadyPausedRequiresFreshIdentityAndReadbackButSendsNoPhysicalAction() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        when(adapter.getStatus(any())).thenReturn(status(PrinterStatus.PAUSED));
        assertThat(service(true).pause(command, () -> { }, () -> { }).code()).isEqualTo("ALREADY_PAUSED");
        verify(identities).verifyRemoteFresh(printer); verify(adapter, never()).pause(any());
    }

    @Test void rrfPausingDoesNotConfirmUntilTheDeviceReportsFinalPaused() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.RRF);
        printer.setFirmwareType("RRF");
        var reads = new AtomicInteger();
        when(adapter.getStatus(any())).thenAnswer(invocation -> {
            int read = reads.incrementAndGet();
            if (read <= 2) return status(PrinterStatus.PRINTING);
            if (read == 3) {
                verify(printers, never()).updateById(any(Printer.class));
                return status(PrinterStatus.PAUSED, "pausing");
            }
            return status(PrinterStatus.PAUSED);
        });
        assertThat(service(true).pause(command, () -> { }, () -> { }))
                .isEqualTo(new CloudControlWire.Outcome("CONFIRMED", "DEVICE_PAUSED"));
        assertThat(reads.get()).isEqualTo(4);
        verify(adapter).pause(any());
    }

    @Test void rrfRemainingInPausingTimesOutWithoutConfirmingOrRepeatingPause() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.RRF);
        printer.setFirmwareType("RRF");
        when(adapter.getStatus(any())).thenReturn(status(PrinterStatus.PRINTING), status(PrinterStatus.PRINTING),
                status(PrinterStatus.PAUSED, "pausing"));
        assertThat(service(true).pause(command, () -> { }, () -> { }))
                .isEqualTo(new CloudControlWire.Outcome("UNKNOWN", "DEVICE_CONFIRMATION_TIMEOUT"));
        verify(adapter).pause(any()); verify(printers, never()).updateById(any(Printer.class));
    }

    @Test void alreadyPausingWaitsForFinalPauseAndNeverSendsAnotherM25() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.RRF);
        printer.setFirmwareType("RRF");
        when(adapter.getStatus(any())).thenReturn(status(PrinterStatus.PAUSED, "pausing"),
                status(PrinterStatus.PAUSED, "pausing"), status(PrinterStatus.PAUSED, "pausing"), status(PrinterStatus.PAUSED));
        assertThat(service(true).pause(command, () -> { }, () -> { }).status()).isEqualTo("CONFIRMED");
        verify(adapter, never()).pause(any());
    }

    @ParameterizedTest @ValueSource(strings = {"busy", "resuming", "starting"})
    void preparingOrAnotherTransitionNeverTriggersABlindPause(String rawState) {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.RRF);
        printer.setFirmwareType("RRF");
        when(adapter.getStatus(any())).thenReturn(status(PrinterStatus.PREPARING, rawState));
        assertThat(service(true).pause(command, () -> { }, () -> { }))
                .isEqualTo(new CloudControlWire.Outcome("REJECTED", "DEVICE_STATE_CHANGED"));
        verify(adapter, never()).pause(any()); verify(printers, never()).updateById(any(Printer.class));
    }

    @Test void noActionWhenExecutingReceiptFailsOrAuthorizationIsRevokedImmediatelyAfterIt() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        when(adapter.getStatus(any())).thenReturn(status(PrinterStatus.PRINTING));
        assertThat(service(true).pause(command, () -> { }, () -> { throw new IllegalStateException(); }).status()).isEqualTo("UNKNOWN");
        var allowed = new AtomicBoolean(true);
        assertThat(service(true).pause(command, () -> {
            if (!allowed.get()) throw new CurrentExecutionPauseService.Rejected("REJECTED", "CONTROL_DISABLED");
        }, () -> allowed.set(false)).status()).isEqualTo("UNKNOWN");
        verify(adapter, never()).pause(any());
    }

    @Test void expiryIsRecheckedAfterAcquiringLocalLock() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        var checkpoints = new AtomicInteger();
        var outcome = service(true).pause(command, () -> {
            if (checkpoints.incrementAndGet() == 2) throw new CurrentExecutionPauseService.Rejected("EXPIRED", "COMMAND_EXPIRED");
        }, () -> { });
        assertThat(outcome).isEqualTo(new CloudControlWire.Outcome("EXPIRED", "COMMAND_EXPIRED"));
        verify(adapter, never()).getStatus(any()); verify(adapter, never()).pause(any());
    }

    @Test void offlineMonitorDisabledAndMismatchedExecutionCannotPause() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        assertThat(service(false).pause(command, () -> { }, () -> { }).code()).isEqualTo("MONITOR_DISABLED");
        printer.setMonitorEnabled(false);
        assertThat(service(true).pause(command, () -> { }, () -> { }).code()).isEqualTo("MONITOR_DISABLED");
        printer.setMonitorEnabled(true); execution.setExecutionUid(uuid());
        assertThat(service(true).pause(command, () -> { }, () -> { }).code()).isEqualTo("EXECUTION_CHANGED");
        verify(adapter, never()).pause(any());
    }

    @Test void changedFilenameOrUnknownIdentityRefusesPhysicalAction() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        var changed = status(PrinterStatus.PRINTING);
        when(adapter.getStatus(any())).thenReturn(new PrinterDeviceStatus(changed.status(), changed.rawState(), null,
                "another.gcode", changed.progress(), null, null, null, null, changed.printDuration(), null, null,
                changed.filePosition(), changed.fileSize(), null, false, false));
        assertThat(service(true).pause(command, () -> { }, () -> { }).code()).isEqualTo("EXECUTION_CONTINUITY_UNKNOWN");
        doThrow(new BusinessException(409, "IDENTITY_UNVERIFIED"))
                .when(identities).verifyRemoteFresh(printer);
        assertThat(service(true).pause(command, () -> { }, () -> { }).code()).isEqualTo("IDENTITY_UNVERIFIED");
        verify(adapter, never()).pause(any());
    }

    @Test void samePublicFilenameInAnotherDirectoryDoesNotProveExecutionContinuity() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        execution.setObservedFilename("folder-a/part.gcode");
        var current = status(PrinterStatus.PRINTING);
        when(adapter.getStatus(any())).thenReturn(new PrinterDeviceStatus(current.status(), current.rawState(), null,
                "folder-b/part.gcode", current.progress(), null, null, null, null, current.printDuration(), null, null,
                current.filePosition(), current.fileSize(), null, false, false));
        assertThat(service(true).pause(command, () -> { }, () -> { }).code()).isEqualTo("EXECUTION_CONTINUITY_UNKNOWN");
        verify(adapter, never()).pause(any());
    }

    @Test void waitsBrieflyForLocalCompetitionAndNeverQueuesAnUnboundedPause() throws Exception {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var holder = Thread.ofPlatform().start(() -> PrinterLock.execute(9321L, cache, () -> {
            locked.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return null;
        }));
        try {
            assertThat(locked.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(service(true).pause(command, () -> { }, () -> { }).code()).isEqualTo("PRINTER_BUSY");
            verify(adapter, never()).pause(any());
        } finally { release.countDown(); holder.join(2000); }
    }

    @Test void acceptingPauseWithoutDeviceConfirmationRemainsUnknownAndIsNotBlindlyRetried() {
        when(adapter.protocolType()).thenReturn(PrinterProtocolType.KLIPPER);
        when(adapter.getStatus(any())).thenReturn(status(PrinterStatus.PRINTING));
        assertThat(service(true).pause(command, () -> { }, () -> { }).code()).isEqualTo("DEVICE_CONFIRMATION_TIMEOUT");
        verify(adapter).pause(any()); verify(printers, never()).updateById(any(Printer.class));
        verify(jobs, never()).getById(anyLong());
    }

    private CurrentExecutionPauseService service(boolean monitoring) {
        return new CurrentExecutionPauseService(printers, executions, jobs, new PrinterProtocolAdapterFactory(List.of(adapter)),
                identities, cache, events, transactions, monitoring);
    }
    private PrinterDeviceStatus status(PrinterStatus state) {
        return status(state, state == PrinterStatus.PAUSED ? "paused" : "printing");
    }
    private PrinterDeviceStatus status(PrinterStatus state, String rawState) {
        return new PrinterDeviceStatus(state, rawState, null, "part.gcode",
                BigDecimal.TEN, null, null, null, null, BigDecimal.valueOf(10), null, null,
                BigDecimal.valueOf(1000), BigDecimal.valueOf(10000), null, false, false);
    }
    private String uuid() { return UUID.randomUUID().toString(); }
}
