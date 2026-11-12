package com.example.farm.service;

import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.service.impl.CanonicalPrinterStateServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Uses only an isolated in-memory SQLite connection and mocked local repositories. */
class CanonicalExecutionKindPersistenceTest {
    private final PrinterMapper printers = mock(PrinterMapper.class);
    private final PrinterExecutionService executions = mock(PrinterExecutionService.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private CanonicalPrinterStateServiceImpl service;
    private Printer printer;
    private PrinterExecution execution;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SingleConnectionDataSource(DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE farm_installation_identity (id INTEGER PRIMARY KEY, source_instance_id TEXT NOT NULL)");
        jdbc.update("INSERT INTO farm_installation_identity (id,source_instance_id) VALUES (1,?)", UUID.randomUUID().toString());
        jdbc.execute("CREATE TABLE farm_printer_canonical_state (printer_id INTEGER PRIMARY KEY, device_boot_id TEXT NOT NULL,"
                + " observation_revision INTEGER NOT NULL, descriptor_revision INTEGER NOT NULL, descriptor_json TEXT NOT NULL,"
                + " model_json TEXT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        printer = new Printer();
        printer.setId(11L);
        printer.setDeviceUid(UUID.randomUUID().toString());
        printer.setName("Synthetic printer");
        printer.setFirmwareType("KLIPPER");
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("ONLINE");
        printer.setLastSeenAt(LocalDateTime.now());
        when(printers.selectById(printer.getId())).thenReturn(printer);
        execution = new PrinterExecution();
        execution.setExecutionUid(UUID.randomUUID().toString());
        execution.setFirstObservedAt(LocalDateTime.now());
        execution.setState("ACTIVE");
        execution.setSource("EXTERNAL");
        execution.setHasSeenPaused(true);
        execution.setHasSeenProcessing(false);
        when(executions.findActive(printer.getId())).thenReturn(execution);
        service = restartedService();
    }

    @AfterEach
    void closeIsolatedConnection() {
        if (dataSource != null) dataSource.destroy();
    }

    @ParameterizedTest
    @EnumSource(value = CanonicalPrinterModel.ExecutionKind.class, names = {"PRINT", "SIMULATION"})
    void retainsKindAcrossRestartDescriptorRebuildAndRepeatedIdle(CanonicalPrinterModel.ExecutionKind kind) throws Exception {
        assertThat(service.persist(printer, observation(kind))).isTrue();
        var activeModel = storedModel();
        assertThat(activeModel.observation().currentExecution().kind()).isEqualTo(kind);
        finishExecution();
        execution.setHasSeenProcessing(kind == CanonicalPrinterModel.ExecutionKind.SIMULATION);
        printer.setName("Changed descriptor");
        service = restartedService();

        assertThat(service.persist(printer, observation(null))).isTrue();
        var terminal = storedModel();
        assertThat(terminal.descriptor().revision()).isEqualTo(activeModel.descriptor().revision() + 1);
        assertThat(terminal.observation().deviceBootId()).isNotEqualTo(activeModel.observation().deviceBootId());
        assertUnknownOutcomeWithKind(terminal, kind);
        service = restartedService();
        assertThat(service.persist(printer, observation(null))).isTrue();
        assertUnknownOutcomeWithKind(storedModel(), kind);
    }

    @Test
    void damagedPreviousJsonDoesNotBlockFreshStateOrInventKind() throws Exception {
        assertThat(service.persist(printer, observation(CanonicalPrinterModel.ExecutionKind.PRINT))).isTrue();
        jdbc.update("UPDATE farm_printer_canonical_state SET model_json=? WHERE printer_id=?", "invalid json", printer.getId());
        finishExecution();
        execution.setHasSeenProcessing(true);

        assertThat(restartedService().persist(printer, observation(null))).isTrue();

        assertUnknownOutcomeWithKind(storedModel(), CanonicalPrinterModel.ExecutionKind.UNKNOWN);
    }

    @Test
    void changedDeviceIdentityDoesNotInheritPreviousExecutionKind() throws Exception {
        assertThat(service.persist(printer, observation(CanonicalPrinterModel.ExecutionKind.SIMULATION))).isTrue();
        finishExecution();
        printer.setDeviceUid(UUID.randomUUID().toString());

        assertThat(restartedService().persist(printer, observation(null))).isTrue();

        assertUnknownOutcomeWithKind(storedModel(), CanonicalPrinterModel.ExecutionKind.UNKNOWN);
        assertThat(storedModel().identity().deviceUid().toString()).isEqualTo(printer.getDeviceUid());
    }

    private void assertUnknownOutcomeWithKind(CanonicalPrinterModel model, CanonicalPrinterModel.ExecutionKind kind) {
        var result = model.observation().lastExecutionResult();
        assertThat(result.executionId().toString()).isEqualTo(execution.getExecutionUid());
        assertThat(result.kind()).isEqualTo(kind);
        assertThat(result.outcome()).isEqualTo(CanonicalPrinterModel.ExecutionOutcome.UNKNOWN);
        assertThat(result.evidence()).isEqualTo(CanonicalPrinterModel.ExecutionEvidence.UNCONFIRMED);
        assertThat(result.reasonCode()).isEqualTo("TERMINAL_UNCONFIRMED");
    }

    private void finishExecution() {
        execution.setState("UNKNOWN");
        execution.setLastRawState("idle");
        execution.setEndedAt(LocalDateTime.now());
        when(executions.findActive(printer.getId())).thenReturn(null);
        when(executions.findLastFinished(printer.getId())).thenReturn(execution);
    }

    private PrinterObservation observation(CanonicalPrinterModel.ExecutionKind kind) {
        Instant now = Instant.now();
        var unavailable = new PrinterObservation.Measurement(null, PrinterObservation.Quality.UNAVAILABLE, null);
        var current = kind == null ? null : new PrinterObservation.Execution("synthetic.gcode",
                kind == CanonicalPrinterModel.ExecutionKind.SIMULATION, unavailable, unavailable, unavailable,
                unavailable, unavailable, unavailable, unavailable);
        return new PrinterObservation(now, now, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, kind == null ? PrinterObservation.OperationState.IDLE
                : kind == CanonicalPrinterModel.ExecutionKind.SIMULATION ? PrinterObservation.OperationState.SIMULATING
                : PrinterObservation.OperationState.PAUSED, List.of(), List.of(), null, current);
    }

    private CanonicalPrinterStateServiceImpl restartedService() {
        return new CanonicalPrinterStateServiceImpl(jdbc, printers, executions, json);
    }

    private CanonicalPrinterModel storedModel() throws Exception {
        return json.readValue(jdbc.queryForObject("SELECT model_json FROM farm_printer_canonical_state WHERE printer_id=?",
                String.class, printer.getId()), CanonicalPrinterModel.class);
    }
}
