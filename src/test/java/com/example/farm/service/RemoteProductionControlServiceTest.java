package com.example.farm.service;

import com.example.farm.cloud.CloudControlWire;
import com.example.farm.cloud.CloudRemoteCommandInbox;
import com.example.farm.common.utils.InMemoryCache;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.protocol.HeaterControlChannel;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.protocol.RuntimeControlChannel;
import com.example.farm.protocol.observation.PrinterObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure protocol/database mocks; no real printer or business database is contacted. */
class RemoteProductionControlServiceTest {
    @ParameterizedTest @EnumSource(value = PrinterProtocolType.class, names = {"KLIPPER", "RRF"})
    void externalResumeWaitsForRunningAndNeverCreatesAFarmJob(PrinterProtocolType protocol) {
        var f = new Fixture(protocol, "RESUME");
        when(f.adapter.getStatus(any())).thenReturn(f.status("paused", 50), f.status("paused", 50), f.status("resuming", 50), f.status("printing", 50));
        assertThat(f.apply()).isEqualTo(new CloudControlWire.Outcome("CONFIRMED", "DEVICE_RESUMED"));
        verify(f.adapter).resume(any()); verifyNoInteractions(f.jobs);
    }

    @Test void farmResumeUpdatesOnlyItsVerifiedLocalJobAfterReadback() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, "RESUME"); var job = f.farm(); job.setStatus("PAUSED");
        when(f.adapter.getStatus(any())).thenReturn(f.status("paused", 50), f.status("paused", 50), f.status("printing", 50));
        assertThat(f.apply().status()).isEqualTo("CONFIRMED"); assertThat(job.getStatus()).isEqualTo("PRINTING");
        verify(f.events).publishJobStatus(job);
    }

    @Test void alreadyRunningReadsTheSameExecutionWithoutSendingAnotherResume() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, "RESUME"); when(f.adapter.getStatus(any())).thenReturn(f.status("printing", 50));
        assertThat(f.apply().code()).isEqualTo("ALREADY_RUNNING"); verify(f.adapter, never()).resume(any());
    }

    @Test void noPhysicalActionWhenTheExecutionOrAuthorizationChangesAfterReceipt() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, "RESUME"); when(f.adapter.getStatus(any())).thenReturn(f.status("paused", 50));
        assertThat(f.service.apply(f.command, () -> { }, () -> { throw new IllegalStateException("not acknowledged"); }).status()).isEqualTo("UNKNOWN");
        verify(f.adapter, never()).resume(any());
        var checks = new AtomicInteger();
        var outcome = f.service.apply(f.command, () -> { if (checks.incrementAndGet() >= 4) throw new CurrentExecutionPauseService.Rejected("EXPIRED", "COMMAND_EXPIRED"); }, () -> { });
        assertThat(outcome.status()).isEqualTo("UNKNOWN"); verify(f.adapter, never()).resume(any());
    }

    @Test void rrfCancellationWaitsForTruePauseThenStableIdleBeforeClosingExternalExecution() {
        var f = new Fixture(PrinterProtocolType.RRF, "CANCEL");
        when(f.adapter.getStatus(any())).thenReturn(f.status("processing", 50), f.status("processing", 50),
                f.status("pausing", 50), f.status("paused", 50), f.status("paused", 50), f.status("cancelling", 50), f.status("idle", 50), f.status("idle", 50));
        when(f.executions.finishNonFarmExecution(any(), eq("CANCELLED"), anyString())).thenReturn(true);
        assertThat(f.apply().code()).isEqualTo("DEVICE_CANCELLED"); verify(f.adapter).pause(any()); verify(f.adapter).requestCancel(any());
        verify(f.executions).finishNonFarmExecution(any(), eq("CANCELLED"), anyString()); verifyNoInteractions(f.jobs);
    }

    @Test void alreadyPausingRrfDoesNotSendAnotherPauseAndFarmBindingIsNotReleasedUntilTerminal() {
        var f = new Fixture(PrinterProtocolType.RRF, "CANCEL"); var job = f.farm();
        when(f.adapter.getStatus(any())).thenReturn(f.status("pausing", 50), f.status("pausing", 50), f.status("paused", 50),
                f.status("paused", 50), f.status("cancelling", 50), f.status("idle", 50), f.status("idle", 50));
        when(f.executions.finishFarmExecution(any(), any(), eq(job), eq(PrintJobStatus.CANCELLED), anyString())).thenReturn(true);
        assertThat(f.apply().status()).isEqualTo("CONFIRMED"); verify(f.adapter, never()).pause(any());
        verify(f.executions).finishFarmExecution(any(), any(), eq(job), eq(PrintJobStatus.CANCELLED), anyString());
    }

    @Test void rrfCancellationWithoutTargetTerminalEvidenceNeverUnbindsFarmJob() {
        var f = new Fixture(PrinterProtocolType.RRF, "CANCEL"); f.farm(); f.lastFilename = "different.gcode";
        when(f.adapter.getStatus(any())).thenReturn(f.status("paused", 50), f.status("paused", 50), f.status("paused", 50), f.status("idle", 50));
        assertThat(f.apply().status()).isEqualTo("UNKNOWN"); verify(f.executions, never()).finishFarmExecution(any(), any(), any(), any(), any());
    }

    @Test void klipperCancellationRequiresExplicitCancelledNotUnrelatedIdle() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, "CANCEL");
        when(f.adapter.getStatus(any())).thenReturn(f.status("printing", 50), f.status("printing", 50), f.status("printing", 50), f.status("cancelled", 50));
        when(f.executions.finishNonFarmExecution(any(), anyString(), anyString())).thenReturn(true);
        assertThat(f.apply().code()).isEqualTo("DEVICE_CANCELLED"); verify(f.adapter).requestCancel(any());
    }

    @ParameterizedTest @ValueSource(strings = {"SET_FAN_SPEED", "SET_SPEED_FACTOR", "SET_FLOW_FACTOR"})
    void runtimeSettingsUseExactCasAndConfirmOnlyRequestedValue(String action) {
        var f = new Fixture(PrinterProtocolType.KLIPPER, action);
        when(f.adapter.getStatus(any())).thenReturn(f.status("printing", 50), f.status("printing", 50), f.status("printing", 75));
        assertThat(f.apply().status()).isEqualTo("CONFIRMED"); verify(f.adapter).setRuntimeControl(any(), eq(f.route), eq(75));
    }

    @Test void evenSmallCasChangesAndWrongToolRejectBeforeAnyWrite() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, "SET_FAN_SPEED"); f.percent = new BigDecimal("50.1");
        when(f.adapter.getStatus(any())).thenAnswer(invocation -> f.status("printing", 50));
        assertThat(f.apply().code()).isEqualTo("CONTROL_VALUE_CHANGED"); verify(f.adapter, never()).setRuntimeControl(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        var flow = new Fixture(PrinterProtocolType.RRF, "SET_FLOW_FACTOR"); flow.tool = "different";
        when(flow.adapter.getStatus(any())).thenAnswer(invocation -> flow.status("processing", 50));
        assertThat(flow.apply().code()).isEqualTo("TOOL_CHANGED");
    }

    @Test void inconsistentSampleBeforeActionRejectsAndAfterActionCanOnlyRetryReadback() {
        var f = new Fixture(PrinterProtocolType.RRF, "RESUME"); f.inconsistent = true;
        var inconsistent = f.status("paused", 50); f.inconsistent = false;
        when(f.adapter.getStatus(any())).thenReturn(inconsistent);
        assertThat(f.apply().status()).isEqualTo("REJECTED"); verify(f.adapter, never()).resume(any());
        when(f.adapter.getStatus(any())).thenReturn(f.status("paused", 50), f.status("paused", 50), inconsistent, f.status("processing", 50));
        assertThat(f.apply().status()).isEqualTo("CONFIRMED"); verify(f.adapter, times(1)).resume(any());
    }

    @Test void heatersOffPrechecksTheCompleteSetAndRecordsPartialApplicationWithoutReplay() {
        var f = new Fixture(PrinterProtocolType.RRF, "HEATERS_OFF");
        var hot = f.status("idle", 50); var firstOff = f.status("idle", 0);
        when(f.adapter.getStatus(any())).thenReturn(hot, hot, hot, firstOff, firstOff);
        doThrow(new IllegalStateException("uncertain response")).when(f.adapter).setHeaterTarget(any(), eq(f.bed), eq(0));
        assertThat(f.apply()).isEqualTo(new CloudControlWire.Outcome("UNKNOWN", "PARTIAL_APPLIED"));
        verify(f.inbox).channelApplied(any(), eq("a-tool")); verify(f.adapter, times(1)).setHeaterTarget(any(), eq(f.nozzle), eq(0));
        verify(f.adapter, times(1)).setHeaterTarget(any(), eq(f.bed), eq(0));
    }

    @Test void heatersOffCannotSilentlyOmitAnUnknownHeater() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, "HEATERS_OFF"); f.extraHeater = true;
        when(f.adapter.getStatus(any())).thenReturn(f.status("idle", 50));
        assertThat(f.apply().code()).isEqualTo("HEATER_UNSUPPORTED"); verify(f.adapter, never()).setHeaterTarget(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test void heatersOffRequiresAFinalCompleteSnapshotAndCannotReportSuccessAfterAnotherConsoleReheatsTheFirstChannel() {
        var f = new Fixture(PrinterProtocolType.RRF, "HEATERS_OFF");
        var hot = f.status("idle", 50); var firstOff = f.status("idle", 0); f.bedOff = true;
        var allOff = f.status("idle", 0); var reheated = f.status("idle", 50);
        when(f.adapter.getStatus(any())).thenReturn(hot, hot, hot, firstOff, firstOff, allOff, reheated);
        assertThat(f.apply()).isEqualTo(new CloudControlWire.Outcome("UNKNOWN", "PARTIAL_APPLIED"));
        verify(f.adapter).setHeaterTarget(any(), eq(f.nozzle), eq(0)); verify(f.adapter).setHeaterTarget(any(), eq(f.bed), eq(0));
    }

    @Test void flowReadbackCannotConfirmAnIdenticalValueFromAChangedPhysicalDrive() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, "SET_FLOW_FACTOR");
        var original = f.status("printing", 50); f.extruder = "extruder1"; var replaced = f.status("printing", 75);
        when(f.adapter.getStatus(any())).thenReturn(original, original, replaced);
        assertThat(f.apply()).isEqualTo(new CloudControlWire.Outcome("UNKNOWN", "TOOL_CHANGED"));
        verify(f.adapter).setRuntimeControl(any(), any(), eq(75));
    }

    private static final class Fixture {
        final PrinterService printers = mock(PrinterService.class); final PrinterExecutionService executions = mock(PrinterExecutionService.class);
        final PrintJobService jobs = mock(PrintJobService.class); final HeaterTargetControlService guard = mock(HeaterTargetControlService.class);
        final PrinterProtocolAdapter adapter = mock(PrinterProtocolAdapter.class); final WebSocketEventPublisher events = mock(WebSocketEventPublisher.class);
        final CloudRemoteCommandInbox inbox = mock(CloudRemoteCommandInbox.class); final Printer printer = new Printer(); final PrinterExecution execution = new PrinterExecution();
        final PrinterProtocolType protocol; final CloudControlWire.Command command; final RemoteProductionControlService service; final RuntimeControlChannel route;
        final HeaterControlChannel nozzle = new HeaterControlChannel("a-tool", PrinterObservation.HeaterRole.TOOL, null, null, 0, null);
        final HeaterControlChannel bed = new HeaterControlChannel("b-bed", PrinterObservation.HeaterRole.BED, null, null, null, 0);
        String lastFilename = "part.gcode", tool = "extruder", extruder = "extruder"; boolean inconsistent, extraHeater, bedOff; BigDecimal percent;
        Fixture(PrinterProtocolType protocol, String action) {
            this.protocol = protocol; boolean idle = action.equals("HEATERS_OFF"); long now = System.currentTimeMillis();
            command = new CloudControlWire.Command(3, uuid(), action, uuid(), 1, uuid(), 1, uuid(), uuid(), "9325", idle ? null : uuid(),
                    "part.gcode", 10000L, 100L, 2, uuid(), "test-operator", now, now + 30000, null, null, null, uuid(), 1L,
                    idle ? "IDLE" : action.equals("RESUME") ? "PAUSED" : "PRINTING", action.equals("SET_FAN_SPEED") ? "fan" : null,
                    action.startsWith("SET_") ? 75 : null, action.startsWith("SET_") ? BigDecimal.valueOf(50) : null,
                    action.equals("SET_FLOW_FACTOR") ? "extruder" : null, idle ? List.of(new CloudControlWire.HeaterTarget("a-tool", BigDecimal.valueOf(50)), new CloudControlWire.HeaterTarget("b-bed", BigDecimal.valueOf(60))) : null);
            printer.setId(9325L); printer.setIpAddress("192.0.2.20"); printer.setFirmwareType(protocol.name()); printer.setDeviceUid(command.deviceUid());
            execution.setId(1L); execution.setState("ACTIVE"); execution.setSource("EXTERNAL"); execution.setExecutionUid(command.expectedExecutionId());
            execution.setObservedFilename("part.gcode"); execution.setMaxPrintSeconds(BigDecimal.valueOf(100)); execution.setLastSeenAt(LocalDateTime.now());
            when(printers.getById(anyLong())).thenReturn(printer); when(printers.updateById(any(Printer.class))).thenReturn(true);
            when(guard.context(anyLong(), any())).thenReturn(printer); when(executions.findActive(anyLong())).thenReturn(idle ? null : execution);
            when(adapter.protocolType()).thenReturn(protocol);
            route = new RuntimeControlChannel(action, action.equals("SET_FLOW_FACTOR") ? "extruder" : "fan", action.equals("SET_FLOW_FACTOR") ? "extruder" : null, null);
            when(adapter.inspectRuntimeControl(any(), anyString(), any(), any())).thenReturn(route);
            when(adapter.inspectHeater(any(), eq("a-tool"))).thenReturn(nozzle); when(adapter.inspectHeater(any(), eq("b-bed"))).thenReturn(bed);
            var tx = mock(PlatformTransactionManager.class); when(tx.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
            service = new RemoteProductionControlService(guard, printers, executions, jobs, new PrinterProtocolAdapterFactory(List.of(adapter)), new InMemoryCache(), tx, events, inbox);
        }
        PrintJob farm() {
            execution.setSource("FARM"); execution.setFarmJobId(4L); printer.setCurrentJobId(4L);
            var job = new PrintJob(); job.setId(4L); job.setPrinterId(9325L); job.setStatus("PRINTING");
            when(jobs.getById(4L)).thenReturn(job); when(jobs.updateById(job)).thenReturn(true); return job;
        }
        CloudControlWire.Outcome apply() { return service.apply(command, () -> { }, () -> { }); }
        PrinterDeviceStatus status(String raw, int percentage) {
            var state = switch (raw) { case "paused" -> PrinterObservation.OperationState.PAUSED; case "pausing" -> PrinterObservation.OperationState.PAUSING;
                case "resuming" -> PrinterObservation.OperationState.RESUMING; case "cancelling" -> PrinterObservation.OperationState.CANCELLING;
                case "idle", "cancelled" -> PrinterObservation.OperationState.IDLE; default -> PrinterObservation.OperationState.PRINTING; };
            var fan = new PrinterObservation.Fan("fan", PrinterObservation.FanRole.PART, m(BigDecimal.ONE), m(BigDecimal.valueOf(2000)),
                    m((percent == null ? BigDecimal.valueOf(percentage) : percent).movePointLeft(2)));
            var heaters = new java.util.ArrayList<PrinterObservation.Heater>();
            heaters.add(new PrinterObservation.Heater("a-tool", PrinterObservation.HeaterRole.TOOL, percentage == 0 ? PrinterObservation.HeaterMode.OFF : PrinterObservation.HeaterMode.ACTIVE,
                    m(BigDecimal.valueOf(20)), m(BigDecimal.valueOf(percentage)), m(BigDecimal.valueOf(percentage)), m(BigDecimal.ZERO)));
            heaters.add(new PrinterObservation.Heater("b-bed", PrinterObservation.HeaterRole.BED, bedOff ? PrinterObservation.HeaterMode.OFF : PrinterObservation.HeaterMode.ACTIVE,
                    m(BigDecimal.valueOf(20)), m(bedOff ? BigDecimal.ZERO : BigDecimal.valueOf(60)), m(BigDecimal.valueOf(60)), m(BigDecimal.ZERO)));
            if (extraHeater) heaters.add(new PrinterObservation.Heater("chamber", PrinterObservation.HeaterRole.UNKNOWN, PrinterObservation.HeaterMode.ACTIVE, m(BigDecimal.TEN), m(BigDecimal.TEN), null, null));
            var observation = new PrinterObservation(Instant.now(), Instant.now(), inconsistent ? PrinterObservation.Completeness.INCONSISTENT : PrinterObservation.Completeness.PARTIAL,
                    PrinterObservation.SystemState.READY, state, heaters, List.of(new PrinterObservation.Tool("extruder", List.of("a-tool"), List.of(), List.of(extruder))),
                    tool, null, List.of(fan), new PrinterObservation.Motion(List.of(), m(BigDecimal.valueOf(percentage).movePointLeft(2)), m(BigDecimal.valueOf(percentage).movePointLeft(2))), List.of(),
                    new PrinterObservation.TerminalEvidence(lastFilename, BigDecimal.valueOf(120), state != PrinterObservation.OperationState.IDLE));
            return new PrinterDeviceStatus(state == PrinterObservation.OperationState.IDLE ? PrinterStatus.IDLE : raw.equals("paused") ? PrinterStatus.PAUSED : PrinterStatus.PRINTING,
                    raw, null, "part.gcode", BigDecimal.TEN, null, null, null, null, BigDecimal.valueOf(100), null, null, BigDecimal.valueOf(100), BigDecimal.valueOf(10000), null,
                    null, null, BigDecimal.valueOf(120), BigDecimal.valueOf(1000), observation);
        }
        PrinterObservation.Measurement m(BigDecimal value) { return new PrinterObservation.Measurement(value, PrinterObservation.Quality.GOOD, Instant.now()); }
    }
    private static String uuid() { return UUID.randomUUID().toString(); }
}
