package com.example.farm.protocol.model;

import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.protocol.observation.PrinterObservationParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalPrinterModelMapperTest {
    @Test
    void mapsStableIdentityAndOnlyKnownObservationFields() {
        Instant captured = Instant.parse("2026-11-02T10:00:00Z");
        Printer printer = new Printer();
        printer.setId(42L);
        printer.setDeviceUid("b9ea26c3-19af-4a47-92a2-4204c7b4d887");
        printer.setName("Printer A");
        printer.setFirmwareType("KLIPPER");
        printer.setConnectionStatus("ONLINE");
        printer.setMonitorEnabled(true);
        printer.setConsecutiveFailureCount(0);
        var measurement = new PrinterObservation.Measurement(new BigDecimal("205.0"),
                PrinterObservation.Quality.GOOD, captured);
        var heater = new PrinterObservation.Heater("extruder", PrinterObservation.HeaterRole.TOOL,
                PrinterObservation.HeaterMode.ACTIVE, measurement, measurement, measurement,
                new PrinterObservation.Measurement(null, PrinterObservation.Quality.UNAVAILABLE, captured),
                new PrinterObservation.Measurement(BigDecimal.ZERO, PrinterObservation.Quality.GOOD, captured));
        var observation = new PrinterObservation(captured, captured, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(heater), List.of(new PrinterObservation.Tool("extruder", List.of("extruder"))),
                "extruder", null);

        var model = CanonicalPrinterModelMapper.map(printer,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 1, 1, observation, null, false, captured);

        assertEquals(2, model.modelVersion());
        assertEquals("42", model.identity().localPrinterId());
        assertEquals("b9ea26c3-19af-4a47-92a2-4204c7b4d887", model.identity().deviceUid().toString());
        assertEquals(CanonicalPrinterModel.FirmwareFamily.KLIPPER, model.descriptor().firmwareFamily());
        assertEquals("extruder", model.observation().heaters().getFirst().id());
        assertEquals(new BigDecimal("205.0"), model.observation().heaters().getFirst().currentC().value());
        assertEquals(BigDecimal.ZERO, model.observation().heaters().getFirst().pwmRatio().value());
        assertEquals(CanonicalPrinterModel.Quality.GOOD, model.observation().heaters().getFirst().pwmRatio().quality());
        assertEquals(CanonicalPrinterModel.MeasurementSource.DEVICE,
                model.observation().heaters().getFirst().pwmRatio().source());
        assertEquals(CanonicalPrinterModel.CoordinateSpace.UNKNOWN, model.observation().motion().coordinateSpace());
        assertTrue(model.descriptor().fans().isEmpty());
    }

    @Test
    void rejectsInvalidStableIdentityInsteadOfGuessing() {
        Printer printer = new Printer();
        printer.setId(1L);
        printer.setDeviceUid("not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> CanonicalPrinterModelMapper.map(printer,
                UUID.randomUUID(), UUID.randomUUID(), 1, 1, null, null, false, Instant.now()));
    }

    @Test
    void serializesUnknownFieldsAsExplicitNulls() throws Exception {
        Instant captured = Instant.parse("2026-11-02T10:00:00Z");
        Printer printer = new Printer();
        printer.setId(7L);
        printer.setDeviceUid("5c6b4102-33d7-4748-8e1d-771b3110139f");
        printer.setName("Printer B");
        var model = CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                null, null, false, captured);
        var json = new ObjectMapper().registerModule(new JavaTimeModule()).valueToTree(model);

        assertTrue(json.path("observation").has("motion"));
        assertEquals("UNKNOWN", json.path("observation").path("motion").path("coordinateSpace").asText());
        assertTrue(json.path("descriptor").has("firmwareVersion"));
        assertTrue(json.path("descriptor").get("firmwareVersion").isNull());
    }

    @Test
    void mapsPersistedExecutionIdentityWithoutClaimingExternalStartTime() {
        Instant captured = Instant.parse("2026-11-02T10:00:00Z");
        Printer printer = new Printer();
        printer.setId(9L);
        printer.setDeviceUid("67e76af9-e1df-4230-bce8-3628c87aa7d5");
        var unavailable = new PrinterObservation.Measurement(null,
                PrinterObservation.Quality.UNAVAILABLE, captured);
        var remaining = new PrinterObservation.Measurement(new BigDecimal("79187"),
                PrinterObservation.Quality.GOOD, captured);
        var execution = new PrinterObservation.Execution("part.gcode", false, unavailable, unavailable,
                unavailable, unavailable, unavailable, remaining, unavailable, PrinterObservation.RemainingMethod.SLICER);
        var observation = new PrinterObservation(captured, captured, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.PRINTING,
                List.of(), List.of(), null, execution);
        var persisted = new PrinterExecution();
        persisted.setExecutionUid("d12e83f9-6f05-4e90-bc21-2089f83566b2");
        persisted.setFirstObservedAt(java.time.LocalDateTime.of(2026, 11, 2, 18, 0));
        persisted.setStartedAt(java.time.LocalDateTime.of(2026, 11, 2, 17, 59));
        persisted.setState("ACTIVE");
        persisted.setSource("EXTERNAL");

        var mapped = CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, observation, persisted, true, captured);
        assertEquals(UUID.fromString(persisted.getExecutionUid()), mapped.observation().currentExecution().id());
        assertEquals(CanonicalPrinterModel.ExecutionSource.EXTERNAL, mapped.observation().currentExecution().source());
        assertNull(mapped.observation().currentExecution().startedAt());
        assertEquals("part.gcode", mapped.observation().currentExecution().file().name());
        assertEquals(CanonicalPrinterModel.RemainingMethod.SLICER,
                mapped.observation().currentExecution().estimatedRemaining().method());
        assertEquals(new BigDecimal("79187"), mapped.observation().currentExecution().estimatedRemaining().seconds().value());
        assertEquals(CanonicalPrinterModel.MeasurementSource.SLICER_ESTIMATE,
                mapped.observation().currentExecution().estimatedRemaining().seconds().source());
    }

    @Test
    void mapsOnlyPersistedTerminalEvidenceIntoLastExecutionResult() {
        Instant endedAt = Instant.parse("2026-11-02T10:10:00Z");
        Printer printer = new Printer();
        printer.setId(12L);
        printer.setDeviceUid("d3a64af8-b6d4-46c4-9a34-d2eb2c71f9a4");
        PrinterExecution completed = new PrinterExecution();
        completed.setExecutionUid("f5875710-91fa-4e8d-a8c6-300495d6c71a");
        completed.setState("COMPLETED");
        completed.setLastRawState("complete");
        completed.setHasSeenProcessing(true);
        completed.setEndedAt(java.time.LocalDateTime.of(2026, 11, 2, 18, 10));

        var completedModel = CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, null, null, completed, false, endedAt);
        assertEquals(UUID.fromString(completed.getExecutionUid()),
                completedModel.observation().lastExecutionResult().executionId());
        assertEquals(CanonicalPrinterModel.ExecutionOutcome.COMPLETED,
                completedModel.observation().lastExecutionResult().outcome());
        assertEquals(CanonicalPrinterModel.ExecutionEvidence.DEVICE_EXPLICIT,
                completedModel.observation().lastExecutionResult().evidence());

        completed.setState("UNKNOWN");
        completed.setLastRawState("idle");
        var unknownModel = CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, null, null, completed, false, endedAt);
        assertEquals(CanonicalPrinterModel.ExecutionOutcome.UNKNOWN,
                unknownModel.observation().lastExecutionResult().outcome());
        assertEquals(CanonicalPrinterModel.ExecutionEvidence.UNCONFIRMED,
                unknownModel.observation().lastExecutionResult().evidence());
    }

    @Test
    void mapsDiscoveredFansAxesAndSensorsWithDescriptorReferences() throws Exception {
        Instant captured = Instant.parse("2026-11-02T10:00:00Z");
        Printer printer = new Printer();
        printer.setId(10L);
        printer.setDeviceUid("cc7efef1-4eb2-46c5-aea0-25326981b832");
        printer.setName("Multi channel");
        printer.setFirmwareType("KLIPPER");
        var raw = new ObjectMapper().readTree("""
                {"webhooks":{"state":"ready"},"print_stats":{"state":"standby"},
                 "extruder":{"temperature":200,"target":210},"fan":{"speed":0.3},
                 "gcode_move":{"gcode_position":[1,2,3,0],"speed_factor":1,"extrude_factor":1},
                 "toolhead":{"homed_axes":"xyz"},
                 "filament_switch_sensor runout":{"enabled":true,"filament_detected":false},
                 "display_status":{"progress":0}}
                """);
        var observation = PrinterObservationParser.klipper(raw, captured, captured);

        var model = CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, observation, null, false, captured);

        assertEquals(1, model.descriptor().fans().size());
        assertEquals("fan", model.observation().fans().getFirst().id());
        assertEquals(CanonicalPrinterModel.SupportState.SUPPORTED,
                model.descriptor().capabilities().stream().filter(c -> c.code().equals("READ_FANS"))
                        .findFirst().orElseThrow().support());
        assertEquals(4, model.descriptor().axes().size());
        assertEquals(1, model.descriptor().sensors().size());
        assertEquals("runout", model.descriptor().sensors().getFirst().id().substring("filament_switch_sensor_".length()));
    }

    @Test
    void rejectsVersionIdentityTimestampAndMeasurementQualityViolations() {
        Instant captured = Instant.parse("2026-11-02T10:00:00Z");
        Printer printer = new Printer();
        printer.setId(11L);
        printer.setDeviceUid("40df3ec3-b809-4a1c-b245-42f244aebba5");
        printer.setName("Validated printer");
        var good = new PrinterObservation.Measurement(new BigDecimal("0"), PrinterObservation.Quality.GOOD, captured);
        var heater = new PrinterObservation.Heater("extruder", PrinterObservation.HeaterRole.TOOL,
                PrinterObservation.HeaterMode.ACTIVE, good, good, good, good);
        var observation = new PrinterObservation(captured, captured, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(heater), List.of(new PrinterObservation.Tool("extruder", List.of("extruder"))),
                "extruder", null);
        var model = CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, observation, null, false, captured);

        assertThrows(IllegalArgumentException.class, () -> CanonicalPrinterModelValidator.validate(
                new CanonicalPrinterModel(3, model.identity(), model.descriptor(), model.observation())));
        assertThrows(IllegalArgumentException.class, () -> CanonicalPrinterModelValidator.validate(
                new CanonicalPrinterModel(2,
                        new CanonicalPrinterModel.Identity(model.identity().sourceInstanceId(), null, "11"),
                        model.descriptor(), model.observation())));

        var current = model.observation();
        var reversedTime = new CanonicalPrinterModel.Observation(current.id(), current.deviceBootId(), current.revision(),
                current.descriptorRevision(), captured.plusSeconds(1), captured, current.completeness(),
                current.connection(), current.systemState(), current.operationState(), current.currentToolId(),
                current.heaters(), current.fans(), current.motion(), current.sensors(), current.currentExecution(),
                current.lastExecutionResult(), current.issues());
        assertThrows(IllegalArgumentException.class, () -> CanonicalPrinterModelValidator.validate(
                new CanonicalPrinterModel(2, model.identity(), model.descriptor(), reversedTime)));

        var currentHeater = current.heaters().getFirst();
        var invalidGoodMeasurement = new CanonicalPrinterModel.Measurement<BigDecimal>(null,
                CanonicalPrinterModel.Quality.GOOD, captured, CanonicalPrinterModel.MeasurementSource.DEVICE);
        var invalidHeater = new CanonicalPrinterModel.HeaterState(currentHeater.id(), invalidGoodMeasurement,
                currentHeater.effectiveTargetC(), currentHeater.activeTargetC(), currentHeater.standbyTargetC(),
                currentHeater.pwmRatio(), currentHeater.mode());
        var invalidMeasurementObservation = new CanonicalPrinterModel.Observation(current.id(), current.deviceBootId(),
                current.revision(), current.descriptorRevision(), current.captureStartedAt(), current.observedAt(),
                current.completeness(), current.connection(), current.systemState(), current.operationState(),
                current.currentToolId(), List.of(invalidHeater), current.fans(), current.motion(), current.sensors(),
                current.currentExecution(), current.lastExecutionResult(), current.issues());
        assertThrows(IllegalArgumentException.class, () -> CanonicalPrinterModelValidator.validate(
                new CanonicalPrinterModel(2, model.identity(), model.descriptor(), invalidMeasurementObservation)));
    }
}
