package com.example.farm.protocol.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.example.farm.protocol.model.CanonicalPrinterModel.*;
import static org.assertj.core.api.Assertions.assertThat;

class CanonicalPrinterSummaryTest {
    private static final Instant NOW = Instant.parse("2026-11-05T06:00:00Z");

    @Test void selectsCurrentToolAndExplicitBedAndPreservesRealZero() {
        var summary = CanonicalPrinterSummary.from(model(NOW, NOW, Quality.GOOD), NOW);
        assertThat(summary.stale()).isFalse();
        assertThat(summary.status()).isEqualTo("PRINTING");
        assertThat(summary.toolTemperature()).isEqualTo(205);
        assertThat(summary.toolTarget()).isZero();
        assertThat(summary.bedTemperature()).isEqualTo(60);
        assertThat(summary.progress()).isZero();
        assertThat(summary.printDuration()).isEqualTo(120);
        assertThat(summary.filename()).isEqualTo("part.gcode");
        assertThat(summary.taskSource()).isEqualTo("EXTERNAL");
    }

    @Test void oldOrFutureObservationClearsMeasurementsAndExecution() {
        for (long offset : new long[]{-31, 6}) {
            var summary = CanonicalPrinterSummary.from(model(NOW.plusSeconds(offset), NOW, Quality.GOOD), NOW);
            assertThat(summary.stale()).isTrue();
            assertThat(summary.status()).isEqualTo("UNKNOWN");
            assertThat(summary.progress()).isNull();
            assertThat(summary.toolTemperature()).isNull();
            assertThat(summary.filename()).isNull();
            assertThat(summary.executionId()).isNull();
        }
    }

    @Test void invalidOrIndividuallyExpiredMeasurementsCannotBecomeCurrent() {
        var invalid = CanonicalPrinterSummary.from(model(NOW, NOW, Quality.INVALID), NOW);
        assertThat(invalid.stale()).isFalse();
        assertThat(invalid.progress()).isNull();
        assertThat(invalid.toolTemperature()).isNull();
        var expired = CanonicalPrinterSummary.from(model(NOW, NOW.minusSeconds(31), Quality.GOOD), NOW);
        assertThat(expired.stale()).isFalse();
        assertThat(expired.progress()).isNull();
        assertThat(expired.bedTemperature()).isNull();
    }

    @Test void healthChangesClearValuesAndMonitoringDisableOverridesOldOnlineState() {
        var model = model(NOW, NOW, Quality.GOOD);
        var offline = CanonicalPrinterSummary.from(model.withConnectionHealth(
                new Connection(ConnectionStatus.OFFLINE, true, NOW, 3)), NOW);
        assertThat(offline.status()).isEqualTo("OFFLINE");
        assertThat(offline.toolTemperature()).isNull();
        assertThat(offline.consecutiveFailureCount()).isEqualTo(3);
        var disabled = CanonicalPrinterSummary.from(model.withConnectionHealth(
                new Connection(ConnectionStatus.ONLINE, false, NOW, 0)), NOW);
        assertThat(disabled.status()).isEqualTo("UNKNOWN");
        assertThat(disabled.progress()).isNull();
    }

    private CanonicalPrinterModel model(Instant observed, Instant sampled, Quality quality) {
        var tools = List.of(new ToolDescriptor("tool0", "工具0", List.of("h0"), List.of(), List.of()),
                new ToolDescriptor("tool1", "工具1", List.of("h1"), List.of(), List.of()));
        var heaters = List.of(new HeaterDescriptor("h0", "喷头0", HeaterRole.TOOL, RoleEvidence.PROBED),
                new HeaterDescriptor("h1", "喷头1", HeaterRole.TOOL, RoleEvidence.PROBED),
                new HeaterDescriptor("bed", "热床", HeaterRole.BED, RoleEvidence.PROBED));
        var descriptor = new Descriptor(1, "测试设备", "A-01", FirmwareFamily.RRF, "3.7",
                ConnectionMode.RRF_STANDALONE, MachineMode.FFF, false, "tool0", "bed",
                tools, heaters, List.of(), List.of(), List.of(), List.of(), null, null, null);
        var zero = measurement(0, sampled, quality);
        var execution = new Execution(UUID.randomUUID(), ExecutionSource.EXTERNAL, ExecutionKind.PRINT, null,
                observed, observed, new FileSummary("/private/jobs/part.gcode", null, null), zero,
                ProgressMethod.DEVICE_REPORTED, new Measurement<>(0L, quality, sampled, MeasurementSource.DEVICE),
                new Timing(measurement(120, sampled, quality), measurement(180, sampled, quality), zero, zero, zero, zero),
                new Layers(null, null), zero, null);
        var observation = new Observation(UUID.randomUUID(), UUID.randomUUID(), 2, 1, observed, observed,
                Completeness.COMPLETE, new Connection(ConnectionStatus.ONLINE, true, observed, 0),
                SystemState.READY, OperationState.PRINTING, "tool1",
                List.of(heater("h0", 195, sampled, quality), heater("h1", 205, sampled, quality),
                        heater("bed", 60, sampled, quality)), List.of(),
                new Motion(CoordinateSpace.GCODE, List.of(), null, null), List.of(), execution, null, List.of());
        return new CanonicalPrinterModel(2, new Identity(UUID.randomUUID(), UUID.randomUUID(), "42"), descriptor, observation);
    }

    private HeaterState heater(String id, int temperature, Instant at, Quality quality) {
        var zero = measurement(0, at, quality);
        return new HeaterState(id, measurement(temperature, at, quality), zero, zero, null, zero, HeaterMode.ACTIVE);
    }

    private Measurement<BigDecimal> measurement(int value, Instant at, Quality quality) {
        return new Measurement<>(BigDecimal.valueOf(value), quality, at, MeasurementSource.DEVICE);
    }
}
