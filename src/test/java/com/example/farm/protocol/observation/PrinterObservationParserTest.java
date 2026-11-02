package com.example.farm.protocol.observation;

import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterObservationParserTest {
    private static final Instant START = Instant.parse("2026-11-01T04:00:00Z");
    private static final Instant END = Instant.parse("2026-11-01T04:00:01Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void klipperDistinguishesZeroMissingAndInvalidWithoutInventingBed() throws Exception {
        var status = mapper.readTree("""
                {"webhooks":{"state":"ready"},"print_stats":{"state":"printing",
                  "filename":"part.gcode","print_duration":0,"total_duration":120},
                 "extruder":{"temperature":0,"target":"bad"},"display_status":{"progress":0}}
                """);
        PrinterObservation observation = PrinterObservationParser.klipper(status, START, END);
        assertThat(observation.operationState()).isEqualTo(PrinterObservation.OperationState.PRINTING);
        assertThat(observation.heaters()).hasSize(1);
        assertThat(observation.currentToolId()).isNull();
        assertThat(observation.heaters().getFirst().currentC().value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(observation.heaters().getFirst().currentC().quality()).isEqualTo(PrinterObservation.Quality.GOOD);
        assertThat(observation.heaters().getFirst().effectiveTargetC().quality()).isEqualTo(PrinterObservation.Quality.INVALID);
        assertThat(observation.execution().progressPercent().value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(observation.execution().printSeconds().value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(observation.execution().activeSeconds().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
    }

    @Test
    void klipperMapsDiscoveredChannelsAndLabelsGcodeCoordinates() throws Exception {
        var status = mapper.readTree("""
                {"webhooks":{"state":"ready"},"print_stats":{"state":"standby"},
                 "extruder":{"temperature":210,"target":215},
                 "extruder1":{"temperature":205,"target":210},
                 "heater_generic chamber":{"temperature":30,"target":35},
                 "fan":{"speed":0.4},"heater_fan hotend_fan":{"speed":0.8,"rpm":1200},
                 "gcode_move":{"gcode_position":[12,23,4,7],"speed_factor":1,"extrude_factor":0.95},
                 "toolhead":{"homed_axes":"xy"},
                 "filament_switch_sensor runout":{"enabled":true,"filament_detected":false},
                 "display_status":{"progress":0}}
                """);
        PrinterObservation observation = PrinterObservationParser.klipper(status, START, END);
        assertThat(observation.heaters()).hasSize(3);
        assertThat(observation.tools()).extracting(PrinterObservation.Tool::id)
                .contains("extruder", "extruder1");
        assertThat(observation.fans()).hasSize(2);
        assertThat(observation.fans()).filteredOn(fan -> fan.id().equals("heater_fan_hotend_fan"))
                .singleElement().satisfies(fan -> assertThat(fan.rpm().value()).isEqualByComparingTo("1200"));
        assertThat(observation.motion().axes()).hasSize(4);
        assertThat(observation.motion().axes().getFirst().positionMm().value()).isEqualByComparingTo("12");
        assertThat(observation.motion().axes().getFirst().homed().value()).isTrue();
        assertThat(observation.motion().axes().get(2).homed().value()).isFalse();
        assertThat(observation.sensors()).hasSize(1);
        assertThat(observation.sensors().getFirst().detected().value()).isFalse();
    }

    @Test
    void rrfMapsAvailableFansAndMachineAxesWithoutGuessingMissingChannels() throws Exception {
        var state = mapper.readTree("""
                {"status":"processing","fans":[{"actualValue":0,"rpm":0},{"actualValue":-1,"rpm":-1}],
                 "move":{"axes":[{"letter":"X","machinePosition":12.5,"homed":true},
                 {"letter":"Y","machinePosition":null,"homed":false}]},
                 "sensors":{"filamentMonitors":[{"enableMode":2,"filamentPresent":false},
                 {"enableMode":0,"filamentPresent":null}]}}
                """);
        var tools = mapper.readTree("[{\"number\":2,\"heaters\":[1],\"fans\":[0]}]");
        var observation = PrinterObservationParser.rrf(state, mapper.readTree("{}"),
                mapper.readTree("{\"heaters\":[{},{}] }"), tools, START, END);

        assertThat(observation.fans()).hasSize(2);
        assertThat(observation.fans().getFirst().pwmRatio().value()).isEqualByComparingTo("0");
        assertThat(observation.fans().get(1).rpm().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        assertThat(observation.tools().getFirst().fanIds()).containsExactly("fan-0");
        assertThat(observation.motion().coordinateSpace()).isEqualTo(PrinterObservation.CoordinateSpace.MACHINE);
        assertThat(observation.motion().axes().getFirst().positionMm().value()).isEqualByComparingTo("12.5");
        assertThat(observation.motion().axes().get(1).positionMm().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        assertThat(observation.sensors()).hasSize(2);
        assertThat(observation.sensors().getFirst().enabled().value()).isTrue();
        assertThat(observation.sensors().getFirst().detected().value()).isFalse();
        assertThat(observation.sensors().get(1).detected().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
    }

    @Test
    void rrfRespectsToolAssociationsStandbyAndDistinctTiming() throws Exception {
        var state = mapper.readTree("{\"status\":\"simulating\",\"currentTool\":1}");
        var job = mapper.readTree("""
                {"file":{"fileName":"part.gcode","size":1000,"printTime":7200},
                 "filePosition":500,"duration":1000,"timesLeft":{"file":3500}}
                """);
        var heat = mapper.readTree("""
                {"bedHeaters":[-1],"heaters":[{"current":25,"active":60,"standby":0,"state":"off"},
                 {"current":150,"active":215,"standby":150,"state":"standby"},
                 {"current":20,"active":200,"state":"active"}]}
                """);
        var tools = mapper.readTree("[{\"number\":0,\"heaters\":[2]},{\"number\":1,\"heaters\":[1]}]");
        PrinterObservation observation = PrinterObservationParser.rrf(state, job, heat, tools, START, END);
        assertThat(observation.completeness()).isEqualTo(PrinterObservation.Completeness.PARTIAL);
        assertThat(observation.currentToolId()).isEqualTo("tool-1");
        assertThat(observation.heaters().get(0).role()).isEqualTo(PrinterObservation.HeaterRole.UNKNOWN);
        assertThat(observation.heaters().get(0).effectiveTargetC().value()).isEqualByComparingTo("0");
        assertThat(observation.heaters().get(1).effectiveTargetC().value()).isEqualByComparingTo("150");
        assertThat(observation.heaters().get(1).role()).isEqualTo(PrinterObservation.HeaterRole.TOOL);
        assertThat(observation.execution().simulation()).isTrue();
        assertThat(observation.execution().activeSeconds().value()).isEqualByComparingTo("1000");
        assertThat(observation.execution().slicerEstimatedSeconds().value()).isEqualByComparingTo("7200");
        assertThat(observation.execution().totalSeconds().value()).isNull();
        assertThat(observation.execution().progressPercent().value()).isEqualByComparingTo("50");
    }

    @Test
    void idleRrfDoesNotTreatLastFileAsCurrentExecution() throws Exception {
        PrinterObservation observation = PrinterObservationParser.rrf(
                mapper.readTree("{\"status\":\"idle\"}"), mapper.readTree("{\"lastFileName\":\"old.gcode\"}"),
                mapper.readTree("{}"), mapper.readTree("[]"), START, END);
        assertThat(observation.execution()).isNull();
        assertThat(observation.operationState()).isEqualTo(PrinterObservation.OperationState.IDLE);
    }

    @Test
    void rrfKeepsGroupTimestampsAndDoesNotTreatMissingSensorAsZero() throws Exception {
        Instant jobAt = START.plusMillis(200);
        Instant heatAt = START.plusMillis(700);
        PrinterObservation observation = PrinterObservationParser.rrf(
                mapper.readTree("{\"status\":\"processing\"}"),
                mapper.readTree("{\"duration\":12,\"file\":{\"fileName\":\"p.gcode\",\"size\":100},\"filePosition\":20}"),
                mapper.readTree("{\"heaters\":[{\"sensor\":-1,\"current\":0,\"state\":\"off\"}]}"),
                mapper.readTree("[]"), START, jobAt, heatAt, END);
        assertThat(observation.execution().activeSeconds().observedAt()).isEqualTo(jobAt);
        assertThat(observation.heaters().getFirst().currentC().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        assertThat(observation.heaters().getFirst().effectiveTargetC().observedAt()).isEqualTo(heatAt);
        PrinterObservation reversed = PrinterObservationParser.rrf(
                mapper.readTree("{\"status\":\"processing\"}"), mapper.readTree("{}"), mapper.readTree("{}"),
                mapper.readTree("[]"), START, heatAt, jobAt, END);
        assertThat(reversed.completeness()).isEqualTo(PrinterObservation.Completeness.INCONSISTENT);
        assertThat(reversed.execution()).isNull();
    }

    @Test
    void internalObservationDoesNotChangeLegacyStatusJson() throws Exception {
        PrinterObservation observation = PrinterObservationParser.klipper(mapper.readTree("{}"), START, END);
        PrinterDeviceStatus status = new PrinterDeviceStatus(PrinterStatus.UNKNOWN, "unknown", null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, observation);
        assertThat(mapper.writeValueAsString(status)).doesNotContain("observation", "captureStartedAt");
        assertThat(status.withoutObservation().observation()).isNull();
        PrinterObservation later = PrinterObservationParser.klipper(mapper.readTree("{}"), END, END.plusSeconds(1));
        PrinterDeviceStatus next = new PrinterDeviceStatus(PrinterStatus.UNKNOWN, "unknown", null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, later);
        assertThat(status.withoutObservation()).isEqualTo(next.withoutObservation());
    }

    @Test
    void redisJsonRoundTripPreservesObservationTimeAndQuality() throws Exception {
        var status = mapper.readTree("""
                {"webhooks":{"state":"ready"},"print_stats":{"state":"printing"},
                 "extruder":{"temperature":0},"display_status":{"progress":0}}
                """);
        PrinterObservation observation = PrinterObservationParser.klipper(status, START, END);
        ObjectMapper redisMapper = new ObjectMapper().findAndRegisterModules();
        PrinterObservation decoded = redisMapper.readValue(redisMapper.writeValueAsString(observation), PrinterObservation.class);
        assertThat(decoded.observedAt()).isEqualTo(END);
        assertThat(decoded.heaters().getFirst().currentC().quality()).isEqualTo(PrinterObservation.Quality.GOOD);
        assertThat(decoded.heaters().getFirst().currentC().value()).isEqualByComparingTo("0");
        assertThat(decoded.heaters().getFirst().effectiveTargetC().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
    }
}
