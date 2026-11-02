package com.example.farm.protocol.observation;

import com.example.farm.entity.Printer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterObservationViewTest {
    private static final Instant NOW = Instant.parse("2026-11-01T04:00:30Z");
    private static final Duration MAX_AGE = Duration.ofSeconds(30);

    @Test
    void exposesCurrentOnlyWhenMonitoringHealthyAndWithinAge() {
        Printer printer = online();
        PrinterObservation observation = observation(NOW.minusSeconds(30), PrinterObservation.Completeness.PARTIAL);
        PrinterObservationView view = PrinterObservationView.evaluate(printer, observation, NOW, MAX_AGE);
        assertThat(view.availability()).isEqualTo(PrinterObservationView.Availability.CURRENT);
        assertThat(view.current()).isSameAs(observation);
        assertThat(view.lastKnown()).isSameAs(observation);
    }

    @Test
    void unavailableStatesPreserveLastKnownButNeverExposeCurrent() {
        Printer printer = online();
        PrinterObservation old = observation(NOW.minusSeconds(31), PrinterObservation.Completeness.PARTIAL);
        assertUnavailable(printer, old, PrinterObservationView.Availability.EXPIRED);
        PrinterObservation future = observation(NOW.plusSeconds(1), PrinterObservation.Completeness.PARTIAL);
        assertUnavailable(printer, future, PrinterObservationView.Availability.INVALID_TIMESTAMP);
        PrinterObservation inconsistent = observation(NOW, PrinterObservation.Completeness.INCONSISTENT);
        assertUnavailable(printer, inconsistent, PrinterObservationView.Availability.INCONSISTENT);
        PrinterObservation valid = observation(NOW, PrinterObservation.Completeness.PARTIAL);
        printer.setConsecutiveFailureCount(1);
        assertUnavailable(printer, valid, PrinterObservationView.Availability.COMMUNICATION_FAILURE);
        printer.setConsecutiveFailureCount(0);
        printer.setConnectionStatus("OFFLINE");
        assertUnavailable(printer, valid, PrinterObservationView.Availability.CONNECTION_UNKNOWN);
        printer.setConnectionStatus("ONLINE");
        printer.setConsecutiveFailureCount(null);
        assertUnavailable(printer, valid, PrinterObservationView.Availability.CONNECTION_UNKNOWN);
        printer.setMonitorEnabled(false);
        assertUnavailable(printer, valid, PrinterObservationView.Availability.MONITOR_DISABLED);
        assertThat(PrinterObservationView.evaluate(online(), null, NOW, MAX_AGE).availability())
                .isEqualTo(PrinterObservationView.Availability.NO_OBSERVATION);
    }

    @Test
    void rejectsZeroFreshnessWindow() {
        assertThatThrownBy(() -> PrinterObservationView.evaluate(online(), null, NOW, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertUnavailable(Printer printer, PrinterObservation observation,
                                          PrinterObservationView.Availability expected) {
        PrinterObservationView view = PrinterObservationView.evaluate(printer, observation, NOW, MAX_AGE);
        assertThat(view.availability()).isEqualTo(expected);
        assertThat(view.current()).isNull();
        assertThat(view.lastKnown()).isSameAs(observation);
    }

    private static Printer online() {
        Printer printer = new Printer();
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("ONLINE");
        printer.setConsecutiveFailureCount(0);
        return printer;
    }

    private static PrinterObservation observation(Instant observedAt, PrinterObservation.Completeness completeness) {
        return new PrinterObservation(observedAt, observedAt, completeness, PrinterObservation.SystemState.READY,
                PrinterObservation.OperationState.IDLE, List.of(), List.of(), null, null);
    }
}
