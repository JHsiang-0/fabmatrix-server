package com.example.farm.protocol.model;

import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.protocol.observation.PrinterObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalExecutionKindTest {
    private final Instant captured = Instant.parse("2026-11-12T04:00:00Z");
    private final UUID sourceId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final Printer printer = printer();

    @ParameterizedTest
    @EnumSource(value = CanonicalPrinterModel.ExecutionKind.class, names = {"PRINT", "SIMULATION"})
    void preservesObservedKindForUnknownTerminalWithoutInventingAnOutcome(CanonicalPrinterModel.ExecutionKind kind) {
        var previous = activeModel(kind);
        var finished = finishedExecution();
        finished.setHasSeenProcessing(kind == CanonicalPrinterModel.ExecutionKind.SIMULATION);
        finished.setHasSeenPaused(true);

        var result = terminalModel(finished, previous).observation().lastExecutionResult();

        assertThat(result.kind()).isEqualTo(kind);
        assertThat(result.outcome()).isEqualTo(CanonicalPrinterModel.ExecutionOutcome.UNKNOWN);
        assertThat(result.evidence()).isEqualTo(CanonicalPrinterModel.ExecutionEvidence.UNCONFIRMED);
        assertThat(result.reasonCode()).isEqualTo("TERMINAL_UNCONFIRMED");
    }

    @ParameterizedTest
    @EnumSource(value = CanonicalPrinterModel.ExecutionKind.class, names = {"PRINT", "SIMULATION"})
    void retainsKindFromPreviousTerminalResultAcrossLaterObservations(CanonicalPrinterModel.ExecutionKind kind) {
        var finished = finishedExecution();
        var firstTerminal = terminalModel(finished, activeModel(kind));

        var repeatedTerminal = terminalModel(finished, firstTerminal);

        assertThat(repeatedTerminal.observation().lastExecutionResult().kind()).isEqualTo(kind);
        assertThat(repeatedTerminal.observation().lastExecutionResult().outcome())
                .isEqualTo(CanonicalPrinterModel.ExecutionOutcome.UNKNOWN);
        assertThat(repeatedTerminal.observation().deviceBootId()).isNotEqualTo(firstTerminal.observation().deviceBootId());
    }

    @Test
    void refusesEvidenceFromDifferentInstallationDeviceOrLocalPrinter() {
        var previous = activeModel(CanonicalPrinterModel.ExecutionKind.PRINT);
        var identity = previous.identity();
        var mismatches = List.of(
                new CanonicalPrinterModel.Identity(UUID.randomUUID(), identity.deviceUid(), identity.localPrinterId()),
                new CanonicalPrinterModel.Identity(identity.sourceInstanceId(), UUID.randomUUID(), identity.localPrinterId()),
                new CanonicalPrinterModel.Identity(identity.sourceInstanceId(), identity.deviceUid(), "another-printer"));

        for (var mismatch : mismatches) {
            var unrelated = new CanonicalPrinterModel(previous.modelVersion(), mismatch,
                    previous.descriptor(), previous.observation());
            assertThat(terminalModel(finishedExecution(), unrelated).observation().lastExecutionResult().kind())
                    .isEqualTo(CanonicalPrinterModel.ExecutionKind.UNKNOWN);
        }
    }

    @Test
    void refusesEvidenceForAnotherExecutionOrAnInvalidModel() {
        var previous = activeModel(CanonicalPrinterModel.ExecutionKind.PRINT);
        var anotherExecution = finishedExecution();
        anotherExecution.setExecutionUid(UUID.randomUUID().toString());
        assertThat(terminalModel(anotherExecution, previous).observation().lastExecutionResult().kind())
                .isEqualTo(CanonicalPrinterModel.ExecutionKind.UNKNOWN);
        var invalidVersion = new CanonicalPrinterModel(1, previous.identity(),
                previous.descriptor(), previous.observation());
        assertThat(terminalModel(finishedExecution(), invalidVersion).observation().lastExecutionResult().kind())
                .isEqualTo(CanonicalPrinterModel.ExecutionKind.UNKNOWN);
    }

    @Test
    void doesNotChooseBetweenConflictingKnownKindsForTheSameExecution() {
        var previous = activeModel(CanonicalPrinterModel.ExecutionKind.PRINT);
        var conflicting = new CanonicalPrinterModel.LastExecutionResult(executionId,
                CanonicalPrinterModel.ExecutionOutcome.UNKNOWN, CanonicalPrinterModel.ExecutionKind.SIMULATION,
                CanonicalPrinterModel.ExecutionEvidence.UNCONFIRMED, captured, captured, "TERMINAL_UNCONFIRMED");
        var observation = previous.observation();
        var contradictory = new CanonicalPrinterModel.Observation(observation.id(), observation.deviceBootId(),
                observation.revision(), observation.descriptorRevision(), observation.captureStartedAt(),
                observation.observedAt(), observation.completeness(), observation.connection(), observation.systemState(),
                observation.operationState(), observation.currentToolId(), observation.heaters(), observation.fans(),
                observation.motion(), observation.sensors(), observation.currentExecution(), conflicting, observation.issues());

        var result = terminalModel(finishedExecution(), new CanonicalPrinterModel(previous.modelVersion(),
                previous.identity(), previous.descriptor(), contradictory)).observation().lastExecutionResult();

        assertThat(result.kind()).isEqualTo(CanonicalPrinterModel.ExecutionKind.UNKNOWN);
        assertThat(result.outcome()).isEqualTo(CanonicalPrinterModel.ExecutionOutcome.UNKNOWN);
    }

    @Test
    void doesNotInferKindFromProcessingFlagWithoutPersistedModelEvidence() {
        var finished = finishedExecution();
        finished.setHasSeenProcessing(true);

        assertThat(terminalModel(finished, null).observation().lastExecutionResult().kind())
                .isEqualTo(CanonicalPrinterModel.ExecutionKind.UNKNOWN);
    }

    private CanonicalPrinterModel activeModel(CanonicalPrinterModel.ExecutionKind kind) {
        var active = new PrinterExecution();
        active.setExecutionUid(executionId.toString());
        active.setFirstObservedAt(LocalDateTime.ofInstant(captured.minusSeconds(30), ZoneId.systemDefault()));
        active.setState("ACTIVE");
        active.setSource("EXTERNAL");
        var unavailable = new PrinterObservation.Measurement(null, PrinterObservation.Quality.UNAVAILABLE, null);
        var execution = new PrinterObservation.Execution("synthetic.gcode",
                kind == CanonicalPrinterModel.ExecutionKind.SIMULATION, unavailable, unavailable, unavailable,
                unavailable, unavailable, unavailable, unavailable);
        var observation = new PrinterObservation(captured, captured, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, kind == CanonicalPrinterModel.ExecutionKind.SIMULATION
                ? PrinterObservation.OperationState.SIMULATING : PrinterObservation.OperationState.PAUSED,
                List.of(), List.of(), null, execution);
        return CanonicalPrinterModelMapper.map(printer, sourceId, UUID.randomUUID(), 1, 1,
                observation, active, false, captured);
    }

    private CanonicalPrinterModel terminalModel(PrinterExecution finished, CanonicalPrinterModel previous) {
        var observation = new PrinterObservation(captured, captured, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        return CanonicalPrinterModelMapper.map(printer, sourceId, UUID.randomUUID(), 2, 1,
                observation, null, finished, previous, true, captured);
    }

    private PrinterExecution finishedExecution() {
        var execution = new PrinterExecution();
        execution.setExecutionUid(executionId.toString());
        execution.setState("UNKNOWN");
        execution.setLastRawState("idle");
        execution.setHasSeenProcessing(false);
        execution.setEndedAt(LocalDateTime.ofInstant(captured, ZoneId.systemDefault()));
        return execution;
    }

    private Printer printer() {
        var value = new Printer();
        value.setId(11L);
        value.setDeviceUid(UUID.randomUUID().toString());
        value.setName("Synthetic printer");
        value.setFirmwareType("KLIPPER");
        value.setMonitorEnabled(true);
        value.setConnectionStatus("ONLINE");
        return value;
    }
}
