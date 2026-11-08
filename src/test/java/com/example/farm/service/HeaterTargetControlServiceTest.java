package com.example.farm.service;

import com.example.farm.cloud.CloudControlWire;
import com.example.farm.common.utils.InMemoryCache;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.protocol.HeaterControlChannel;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.protocol.model.CanonicalPrinterModelMapper;
import com.example.farm.protocol.observation.PrinterObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** All protocol calls are mocks. No real printer addresses or persistent business data are used. */
class HeaterTargetControlServiceTest {
    @ParameterizedTest @EnumSource(value = PrinterProtocolType.class, names = {"KLIPPER", "RRF"})
    void confirmsOneChannelSetpointWithoutWaitingForTheActualTemperature(PrinterProtocolType protocol) {
        var f = new Fixture(protocol, false);
        when(f.adapter.getStatus(any())).thenReturn(f.status(200, false), f.status(200, false), f.status(205, false));
        assertThat(f.apply(205, 200)).isEqualTo(new CloudControlWire.Outcome("CONFIRMED", "HEATER_TARGET_CONFIRMED"));
        verify(f.adapter).setHeaterTarget(any(), eq(f.route), eq(205));
        verify(f.printers, never()).updateById(any(Printer.class));
    }

    @Test void bedControlUsesItsOwnLowerSoftwareLimitAndSupportsSwitchingOff() {
        var f = new Fixture(PrinterProtocolType.RRF, true);
        assertThat(f.apply(121, 60).code()).isEqualTo("TEMPERATURE_OUT_OF_RANGE");
        when(f.adapter.getStatus(any())).thenReturn(f.status(60, false), f.status(60, false), f.status(0, false));
        assertThat(f.apply(0, 60).status()).isEqualTo("CONFIRMED");
        verify(f.adapter).setHeaterTarget(any(), eq(f.route), eq(0));
    }

    @Test void actualFirmwareLimitsAreEnforcedAndUnknownLimitsStillPermitAChannelOnlyOff() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false);
        f.route = new HeaterControlChannel(f.id, PrinterObservation.HeaterRole.TOOL, BigDecimal.ZERO, BigDecimal.valueOf(210), null, null);
        when(f.adapter.inspectHeater(any(), anyString())).thenReturn(f.route);
        assertThat(f.apply(220, 200).code()).isEqualTo("TEMPERATURE_OUT_OF_RANGE");
        f.route = new HeaterControlChannel(f.id, PrinterObservation.HeaterRole.TOOL, null, null, null, null);
        when(f.adapter.inspectHeater(any(), anyString())).thenReturn(f.route);
        assertThat(f.apply(205, 200).code()).isEqualTo("HEATER_LIMITS_UNAVAILABLE");
        when(f.adapter.getStatus(any())).thenReturn(f.status(200, false), f.status(200, false), f.status(0, false));
        assertThat(f.apply(0, 200).status()).isEqualTo("CONFIRMED");
        verify(f.adapter).setHeaterTarget(any(), eq(f.route), eq(0));
    }

    @Test void rrfZeroActiveTargetMustActuallySwitchOffBeforeConfirmation() {
        var f = new Fixture(PrinterProtocolType.RRF, true);
        f.modeOverride = PrinterObservation.HeaterMode.ACTIVE;
        var activeZero = f.status(0, false); f.modeOverride = null;
        when(f.adapter.getStatus(any())).thenReturn(activeZero, activeZero, f.status(0, false));
        assertThat(f.apply(0, 0).code()).isEqualTo("HEATER_TARGET_CONFIRMED");
        verify(f.adapter).setHeaterTarget(any(), any(), eq(0));
    }

    @Test void rrfZeroTargetWithoutOffReadbackCannotBeConfirmed() {
        var f = new Fixture(PrinterProtocolType.RRF, true);
        f.modeOverride = PrinterObservation.HeaterMode.ACTIVE;
        when(f.adapter.getStatus(any())).thenReturn(f.status(0, false));
        assertThat(f.apply(0, 0)).isEqualTo(new CloudControlWire.Outcome("UNKNOWN", "DEVICE_CONFIRMATION_TIMEOUT"));
        verify(f.adapter).setHeaterTarget(any(), any(), eq(0));
    }

    @Test void unchangedTargetRequiresFreshReadbackAndDoesNotSendACommand() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false);
        assertThat(f.apply(200, 200).code()).isEqualTo("ALREADY_AT_TARGET");
        verify(f.adapter, never()).setHeaterTarget(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test void staleTargetAndAmbiguousSharedToolCannotOverwriteLocalSettings() {
        var f = new Fixture(PrinterProtocolType.RRF, false);
        assertThat(f.apply(205, 199).code()).isEqualTo("HEATER_TARGET_CHANGED");
        f.shared = true;
        when(f.adapter.getStatus(any())).thenReturn(f.status(200, false));
        assertThat(f.apply(205, 200).code()).isEqualTo("HEATER_UNSUPPORTED");
        verify(f.adapter, never()).setHeaterTarget(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test void compareAndSetDoesNotIgnoreEvenASmallExternalTargetChange() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false);
        assertThat(f.service.apply(f.command(205, new BigDecimal("200.1")), () -> { }, () -> { }).code())
                .isEqualTo("HEATER_TARGET_CHANGED");
        verify(f.adapter, never()).setHeaterTarget(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test void alreadyAtTargetIsExactWhileReadbackMayUseTheDocumentedTolerance() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false);
        when(f.adapter.getStatus(any())).thenReturn(f.status(new BigDecimal("204.7"), false),
                f.status(new BigDecimal("204.7"), false), f.status(205, false));
        assertThat(f.service.apply(f.command(205, new BigDecimal("204.7")), () -> { }, () -> { }).code())
                .isEqualTo("HEATER_TARGET_CONFIRMED");
        verify(f.adapter).setHeaterTarget(any(), any(), eq(205));
    }

    @Test void inconsistentDataCannotAuthorizeAnActionOrConfirmItsResult() {
        var f = new Fixture(PrinterProtocolType.RRF, false);
        when(f.adapter.getStatus(any())).thenReturn(f.status(200, true));
        assertThat(f.apply(205, 200).status()).isEqualTo("REJECTED");
        when(f.adapter.getStatus(any())).thenReturn(f.status(200, false), f.status(200, false), f.status(205, true));
        assertThat(f.apply(205, 200)).isEqualTo(new CloudControlWire.Outcome("UNKNOWN", "EXECUTION_CONTINUITY_UNKNOWN"));
        verify(f.adapter).setHeaterTarget(any(), any(), eq(205));
    }

    @Test void noActionWhenCloudExecutionReceiptFailsOrPolicyChangesAfterAcknowledgement() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false);
        assertThat(f.service.apply(f.command(205, 200), () -> { }, () -> { throw new IllegalStateException(); }).status()).isEqualTo("UNKNOWN");
        var allowed = new AtomicBoolean(true);
        assertThat(f.service.apply(f.command(205, 200), () -> {
            if (!allowed.get()) throw new CurrentExecutionPauseService.Rejected("REJECTED", "CONTROL_DISABLED");
        }, () -> allowed.set(false)).status()).isEqualTo("UNKNOWN");
        verify(f.adapter, never()).setHeaterTarget(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test void freshReadAfterCloudReceiptMustStillMatchTheExpectedTarget() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false);
        when(f.adapter.getStatus(any())).thenReturn(f.status(200, false), f.status(210, false));
        assertThat(f.apply(205, 200)).isEqualTo(new CloudControlWire.Outcome("UNKNOWN", "HEATER_TARGET_CHANGED"));
        verify(f.adapter, never()).setHeaterTarget(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test void expiresAfterTheBoundedLockBeforeAnyProtocolWrite() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false); var checks = new AtomicInteger();
        var result = f.service.apply(f.command(205, 200), () -> {
            if (checks.incrementAndGet() == 2) throw new CurrentExecutionPauseService.Rejected("EXPIRED", "COMMAND_EXPIRED");
        }, () -> { });
        assertThat(result.code()).isEqualTo("COMMAND_EXPIRED");
        verify(f.adapter, never()).setHeaterTarget(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @ParameterizedTest @ValueSource(strings = {"FARM", "EXTERNAL"})
    void printingUsesTheCurrentLocalExecutionWithoutChangingFarmJobs(String source) {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false); f.printing(source);
        when(f.adapter.getStatus(any())).thenReturn(f.status(200, false), f.status(200, false), f.status(205, false));
        assertThat(f.apply(205, 200).status()).isEqualTo("CONFIRMED");
        f.active.setExecutionUid(UUID.randomUUID().toString());
        assertThat(f.apply(205, 200).code()).isEqualTo("EXECUTION_CHANGED");
    }

    @Test void acceptedWriteWithNoMatchingReadbackRemainsUnknownAndIsNeverRepeated() {
        var f = new Fixture(PrinterProtocolType.KLIPPER, false);
        assertThat(f.apply(205, 200)).isEqualTo(new CloudControlWire.Outcome("UNKNOWN", "DEVICE_CONFIRMATION_TIMEOUT"));
        verify(f.adapter).setHeaterTarget(any(), any(), eq(205));
    }

    private static final class Fixture {
        final PrinterService printers = mock(PrinterService.class);
        final PrinterExecutionService executions = mock(PrinterExecutionService.class);
        final CanonicalPrinterStateService models = mock(CanonicalPrinterStateService.class);
        final PrinterIdentityVerifier identities = mock(PrinterIdentityVerifier.class);
        final PrinterProtocolAdapter adapter = mock(PrinterProtocolAdapter.class);
        final Printer printer = new Printer();
        final UUID source = UUID.randomUUID(), device = UUID.randomUUID(), boot = UUID.randomUUID(), execution = UUID.randomUUID();
        final String id, toolId;
        final boolean bed;
        final HeaterTargetControlService service;
        HeaterControlChannel route;
        PrinterExecution active;
        boolean shared;
        PrinterObservation.HeaterMode modeOverride;

        Fixture(PrinterProtocolType protocol, boolean bed) {
            this.bed = bed; id = protocol == PrinterProtocolType.KLIPPER ? bed ? "heater_bed" : "extruder" : bed ? "heater-0" : "heater-1";
            toolId = protocol == PrinterProtocolType.KLIPPER ? "extruder" : "tool-0";
            printer.setId(5511L); printer.setDeviceUid(device.toString()); printer.setFirmwareType(protocol.name());
            printer.setIpAddress("127.0.0.10"); printer.setMonitorEnabled(true); printer.setConnectionStatus("ONLINE"); printer.setLastSeenAt(LocalDateTime.now());
            route = new HeaterControlChannel(id, bed ? PrinterObservation.HeaterRole.BED : PrinterObservation.HeaterRole.TOOL,
                    BigDecimal.ZERO, BigDecimal.valueOf(bed ? 120 : 300), protocol == PrinterProtocolType.RRF && !bed ? 0 : null,
                    protocol == PrinterProtocolType.RRF && bed ? 0 : null);
            when(printers.getById(5511L)).thenReturn(printer); when(adapter.protocolType()).thenReturn(protocol);
            when(adapter.inspectHeater(any(), anyString())).thenAnswer(invocation -> route);
            when(adapter.getStatus(any())).thenAnswer(invocation -> status(bed ? 60 : 200, false));
            refreshModel();
            service = new HeaterTargetControlService(printers, executions, models, identities,
                    new PrinterProtocolAdapterFactory(List.of(adapter)), new InMemoryCache(), true);
        }
        void printing(String sourceKind) {
            active = new PrinterExecution(); active.setState("ACTIVE"); active.setPrinterId(printer.getId()); active.setExecutionUid(execution.toString());
            active.setFirstObservedAt(LocalDateTime.now()); active.setLastSeenAt(LocalDateTime.now()); active.setSource(sourceKind); active.setObservedFilename("part.gcode");
            if ("FARM".equals(sourceKind)) { active.setFarmJobId(5L); printer.setCurrentJobId(5L); }
            when(executions.findActive(5511L)).thenReturn(active); refreshModel();
        }
        void refreshModel() {
            when(models.findLatest(5511L)).thenReturn(CanonicalPrinterModelMapper.map(printer, source, boot, 1, 1,
                    status(bed ? 60 : 200, false).observation(), active, false, Instant.now()));
        }
        CloudControlWire.Outcome apply(int target, int expected) { return service.apply(command(target, expected), () -> { }, () -> { }); }
        CloudControlWire.Command command(int target, int expected) {
            return command(target, BigDecimal.valueOf(expected));
        }
        CloudControlWire.Command command(int target, BigDecimal expected) {
            long now = System.currentTimeMillis();
            return new CloudControlWire.Command(2, UUID.randomUUID().toString(), "SET_HEATER_TARGET", UUID.randomUUID().toString(),
                    1, UUID.randomUUID().toString(), 1, source.toString(), device.toString(), "5511", active == null ? null : execution.toString(),
                    null, null, null, 2, UUID.randomUUID().toString(), "fixture-operator", now, now + 30000,
                    id, target, expected, boot.toString(), 1L, active == null ? "IDLE" : "PRINTING");
        }
        PrinterDeviceStatus status(int target, boolean inconsistent) {
            return status(BigDecimal.valueOf(target), inconsistent);
        }
        PrinterDeviceStatus status(BigDecimal target, boolean inconsistent) {
            Instant at = Instant.now();
            var value = new PrinterObservation.Measurement(target, PrinterObservation.Quality.GOOD, at);
            var heater = new PrinterObservation.Heater(id, bed ? PrinterObservation.HeaterRole.BED : PrinterObservation.HeaterRole.TOOL,
                    modeOverride != null ? modeOverride : target.signum() == 0 ? PrinterObservation.HeaterMode.OFF : PrinterObservation.HeaterMode.ACTIVE,
                    new PrinterObservation.Measurement(BigDecimal.valueOf(21), PrinterObservation.Quality.GOOD, at), value, value, value);
            var tools = bed ? List.<PrinterObservation.Tool>of() : shared ? List.of(new PrinterObservation.Tool(toolId, List.of(id)),
                    new PrinterObservation.Tool("tool-99", List.of(id))) : List.of(new PrinterObservation.Tool(toolId, List.of(id)));
            var progress = new PrinterObservation.Measurement(BigDecimal.TEN, PrinterObservation.Quality.GOOD, at);
            var executionSample = active == null ? null : new PrinterObservation.Execution("part.gcode", false, progress, value, value, value, value, value, value);
            var observation = new PrinterObservation(at, at, inconsistent ? PrinterObservation.Completeness.INCONSISTENT : PrinterObservation.Completeness.COMPLETE,
                    PrinterObservation.SystemState.READY, active == null ? PrinterObservation.OperationState.IDLE : PrinterObservation.OperationState.PRINTING,
                    List.of(heater), tools, bed ? null : toolId, executionSample);
            return new PrinterDeviceStatus(active == null ? PrinterStatus.IDLE : PrinterStatus.PRINTING, active == null ? "idle" : "printing",
                    null, active == null ? null : "part.gcode", BigDecimal.TEN, null, null, null, null, BigDecimal.TEN, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(1000), null, false, false, null, null, observation);
        }
    }
}
