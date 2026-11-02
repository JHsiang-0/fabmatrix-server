package com.example.farm.local;

import com.example.farm.FarmApplication;
import com.zaxxer.hikari.HikariDataSource;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.service.CanonicalPrinterStateService;
import com.example.farm.service.PrinterExecutionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 启动真实 Local profile，覆盖已有 pre-TC007 SQLite 的 bootstrap 顺序。 */
@SpringBootTest(classes = FarmApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalSchemaUpgradeIntegrationTest {

    private static Path dataDirectory;

    @Autowired
    private ConfigurableApplicationContext applicationContext;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private HikariDataSource dataSource;
    @Autowired
    private CanonicalPrinterStateService canonicalPrinterStateService;
    @Autowired
    private PrinterMapper printerMapper;
    @Autowired
    private PrinterExecutionService printerExecutionService;
    @Autowired
    private ObjectMapper objectMapper;
    @LocalServerPort
    private int serverPort;

    @DynamicPropertySource
    static void localDataDirectory(DynamicPropertyRegistry registry) {
        try {
            dataDirectory = Files.createTempDirectory(Path.of("target"), "local-schema-upgrade-");
            createPreTc007Fixture(dataDirectory.resolve("farm.db"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        registry.add("FARM_DATA_DIR", () -> dataDirectory.toString());
        registry.add("logging.file.path", () -> dataDirectory.resolve("logs").toString());
        registry.add("farm.websocket.enabled", () -> "false");
        registry.add("farm.monitor.enabled", () -> "false");
    }

    @AfterAll
    void closeAndRemoveFixture() throws Exception {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        if (dataDirectory != null && Files.exists(dataDirectory)) {
            try (var paths = Files.walk(dataDirectory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            }
        }
    }

    @Test
    void localProfileStartsHealthyAndPreservesLegacyTaskAfterUpgrade() throws Exception {
        assertThat(applicationContext.isActive()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT must_change_password FROM farm_user WHERE id=9104", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM farm_login_session", Integer.class)).isZero();

        var healthRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + serverPort + "/actuator/health"))
                .GET().build();
        var health = java.net.http.HttpClient.newHttpClient()
                .send(healthRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM farm_print_job", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT initialized FROM farm_setup_state WHERE id = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT file_id, printer_id, user_id, operator_id, status, "
                + "root_job_id, attempt_number, file_name_snapshot, printer_name_snapshot, "
                + "machine_number_snapshot, creator_username_snapshot, operator_username_snapshot "
                + "FROM farm_print_job WHERE id = 9101"))
                .containsEntry("file_id", 9102)
                .containsEntry("printer_id", 9103)
                .containsEntry("user_id", 9104)
                .containsEntry("operator_id", 9104)
                .containsEntry("status", "QUEUED")
                .containsEntry("root_job_id", 9101)
                .containsEntry("attempt_number", 1)
                .containsEntry("file_name_snapshot", "startup-legacy.gcode")
                .containsEntry("printer_name_snapshot", "Startup Legacy Printer")
                .containsEntry("machine_number_snapshot", "STARTUP-01")
                .containsEntry("creator_username_snapshot", "startup-operator")
                .containsEntry("operator_username_snapshot", "startup-operator");
        assertThat(jdbcTemplate.queryForObject("SELECT current_job_id FROM farm_printer WHERE id = 9103",
                Integer.class)).isEqualTo(9101);
        String sourceInstanceId = jdbcTemplate.queryForObject(
                "SELECT source_instance_id FROM farm_installation_identity WHERE id = 1", String.class);
        String deviceUid = jdbcTemplate.queryForObject(
                "SELECT device_uid FROM farm_printer WHERE id = 9103", String.class);
        assertThat(UUID.fromString(sourceInstanceId)).isNotNull();
        assertThat(UUID.fromString(deviceUid)).isNotNull();
        assertThat(jdbcTemplate.queryForList("PRAGMA index_list(farm_printer)").stream()
                .map(row -> String.valueOf(row.get("name"))))
                .contains("uk_farm_printer_device_uid");

        new com.example.farm.config.LocalSchemaMigration(jdbcTemplate).run();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_instance_id FROM farm_installation_identity WHERE id = 1", String.class))
                .isEqualTo(sourceInstanceId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT device_uid FROM farm_printer WHERE id = 9103", String.class)).isEqualTo(deviceUid);
        assertThat(jdbcTemplate.queryForObject("SELECT printer_id FROM farm_print_job WHERE id = 9101",
                Integer.class)).isEqualTo(9103);
        assertThat(indexNames()).contains("uk_print_job_retry_of_job", "uk_print_job_root_attempt");
        assertThat(jdbcTemplate.queryForList("PRAGMA table_info(farm_print_job)").stream()
                .map(column -> String.valueOf(column.get("name"))))
                .contains("file_name_snapshot", "printer_name_snapshot", "machine_number_snapshot",
                        "creator_username_snapshot", "operator_username_snapshot", "retry_of_job_id",
                        "root_job_id", "attempt_number");
        assertThat(jdbcTemplate.queryForList("PRAGMA table_info(farm_printer_execution)").stream()
                .map(column -> String.valueOf(column.get("name"))))
                .contains("printer_id", "farm_job_id", "source", "state", "has_seen_processing",
                        "idle_observation_count", "device_uptime_at_start", "ended_at", "end_reason");
        assertThat(jdbcTemplate.queryForList("PRAGMA index_list(farm_printer_execution)").stream()
                .map(row -> String.valueOf(row.get("name"))))
                .contains("uk_printer_execution_active", "idx_printer_execution_printer_state",
                        "idx_printer_execution_job", "uk_printer_execution_uid");
        assertThat(jdbcTemplate.queryForList("PRAGMA table_info(farm_printer_canonical_state)").stream()
                .map(column -> String.valueOf(column.get("name"))))
                .contains("printer_id", "device_boot_id", "observation_revision", "descriptor_revision",
                        "descriptor_json", "model_json");

        Instant observedAt = Instant.now();
        var observation = new PrinterObservation(observedAt, observedAt, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        Printer printer = new Printer();
        printer.setId(9103L);
        assertThat(canonicalPrinterStateService.findLatest(9103L)).isNull();
        assertThat(canonicalPrinterStateService.persist(printer, observation)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT observation_revision FROM farm_printer_canonical_state "
                + "WHERE printer_id = 9103", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT descriptor_revision FROM farm_printer_canonical_state "
                + "WHERE printer_id = 9103", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT model_json FROM farm_printer_canonical_state "
                + "WHERE printer_id = 9103", String.class)).contains("\"modelVersion\":2", "deviceUid");
        var storedModel = canonicalPrinterStateService.findLatest(9103L);
        assertThat(storedModel.modelVersion()).isEqualTo(2);
        assertThat(storedModel.identity().localPrinterId()).isEqualTo("9103");
        assertThat(storedModel.observation().revision()).isEqualTo(1);
        assertThat(canonicalPrinterStateService.findLatest(0)).isNull();
        var restartedStateService = new com.example.farm.service.impl.CanonicalPrinterStateServiceImpl(
                jdbcTemplate, printerMapper, printerExecutionService, objectMapper);
        assertThat(restartedStateService.persist(printer, observation)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT observation_revision FROM farm_printer_canonical_state "
                + "WHERE printer_id = 9103", Integer.class)).isEqualTo(2);
        var recoveredModel = restartedStateService.findLatest(9103L);
        assertThat(recoveredModel.observation().revision()).isEqualTo(2);
        assertThat(recoveredModel.descriptor().revision()).isEqualTo(1);
        assertThat(recoveredModel.observation().deviceBootId()).isNotEqualTo(storedModel.observation().deviceBootId());
        assertThat(recoveredModel.observation().issues()).anyMatch(issue ->
                issue.code() == com.example.farm.protocol.model.CanonicalPrinterModel.IssueCode.CONTINUITY_UNKNOWN);

        jdbcTemplate.update("INSERT INTO farm_printer_execution(printer_id, source, state, started_at, last_seen_at) "
                        + "VALUES (?, 'EXTERNAL', 'ACTIVE', ?, ?)", 9103,
                java.sql.Timestamp.valueOf("2026-11-02 09:00:00"),
                java.sql.Timestamp.valueOf("2026-11-02 09:00:00"));
        new com.example.farm.config.LocalSchemaMigration(jdbcTemplate).run();
        String executionUid = jdbcTemplate.queryForObject(
                "SELECT execution_uid FROM farm_printer_execution WHERE printer_id = 9103", String.class);
        assertThat(UUID.fromString(executionUid)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT first_observed_at FROM farm_printer_execution "
                + "WHERE printer_id = 9103", java.sql.Timestamp.class))
                .isEqualTo(java.sql.Timestamp.valueOf("2026-11-02 09:00:00"));
        new com.example.farm.config.LocalSchemaMigration(jdbcTemplate).run();
        assertThat(jdbcTemplate.queryForObject("SELECT execution_uid FROM farm_printer_execution "
                + "WHERE printer_id = 9103", String.class)).isEqualTo(executionUid);
        jdbcTemplate.update("UPDATE farm_printer_execution SET state = 'UNKNOWN', last_raw_state = 'idle', "
                        + "ended_at = ? WHERE printer_id = 9103",
                java.sql.Timestamp.valueOf("2026-11-02 10:00:00"));
        assertThat(restartedStateService.persist(printer, observation)).isTrue();
        var withUnconfirmedResult = restartedStateService.findLatest(9103L).observation().lastExecutionResult();
        assertThat(withUnconfirmedResult.executionId()).isEqualTo(UUID.fromString(executionUid));
        assertThat(withUnconfirmedResult.outcome())
                .isEqualTo(com.example.farm.protocol.model.CanonicalPrinterModel.ExecutionOutcome.UNKNOWN);
        assertThat(withUnconfirmedResult.evidence())
                .isEqualTo(com.example.farm.protocol.model.CanonicalPrinterModel.ExecutionEvidence.UNCONFIRMED);

        String activeExecutionUid = "5d0e04b5-55bd-4ba9-a3d6-29091b32d83f";
        jdbcTemplate.update("INSERT INTO farm_printer_execution(execution_uid, printer_id, source, state, "
                        + "has_seen_processing, started_at, first_observed_at, last_seen_at) "
                        + "VALUES (?, 9103, 'FARM', 'ACTIVE', 1, ?, ?, ?)", activeExecutionUid,
                java.sql.Timestamp.valueOf("2026-11-02 11:00:00"),
                java.sql.Timestamp.valueOf("2026-11-02 11:00:00"),
                java.sql.Timestamp.valueOf("2026-11-02 11:01:00"));
        var unavailable = new PrinterObservation.Measurement(null,
                PrinterObservation.Quality.UNAVAILABLE, null);
        var executionObservation = new PrinterObservation.Execution("part.gcode", false, unavailable,
                unavailable, unavailable, unavailable, unavailable, unavailable, unavailable);
        var printingObservation = new PrinterObservation(observedAt, observedAt,
                PrinterObservation.Completeness.PARTIAL, PrinterObservation.SystemState.READY,
                PrinterObservation.OperationState.PRINTING, List.of(), List.of(), null, executionObservation);
        assertThat(restartedStateService.persist(printer, printingObservation)).isTrue();
        var currentModel = restartedStateService.findLatest(9103L).observation();
        assertThat(currentModel.currentExecution().id()).isEqualTo(UUID.fromString(activeExecutionUid));
        assertThat(currentModel.lastExecutionResult().outcome())
                .isEqualTo(com.example.farm.protocol.model.CanonicalPrinterModel.ExecutionOutcome.UNKNOWN);

        jdbcTemplate.update("UPDATE farm_printer_execution SET state = 'COMPLETED', last_raw_state = 'complete', "
                        + "ended_at = ? WHERE execution_uid = ?",
                java.sql.Timestamp.valueOf("2026-11-02 11:05:00"), activeExecutionUid);
        assertThat(restartedStateService.persist(printer, observation)).isTrue();
        var finishedModel = restartedStateService.findLatest(9103L).observation();
        assertThat(finishedModel.currentExecution()).isNull();
        assertThat(finishedModel.lastExecutionResult().executionId()).isEqualTo(UUID.fromString(activeExecutionUid));
        assertThat(finishedModel.lastExecutionResult().outcome())
                .isEqualTo(com.example.farm.protocol.model.CanonicalPrinterModel.ExecutionOutcome.COMPLETED);
        assertThat(finishedModel.lastExecutionResult().evidence())
                .isEqualTo(com.example.farm.protocol.model.CanonicalPrinterModel.ExecutionEvidence.DEVICE_EXPLICIT);
    }

    private static void createPreTc007Fixture(Path databasePath) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath())) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, false));
            runResource(jdbc, "db/init/sqlite-schema.sql");
            jdbc.execute("ALTER TABLE farm_printer DROP COLUMN device_uid");
            jdbc.execute("ALTER TABLE farm_user DROP COLUMN must_change_password");
            jdbc.execute("DROP TABLE farm_login_session");
            jdbc.execute("DROP TABLE farm_print_job");
            jdbc.execute("""
                    CREATE TABLE farm_print_job (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_id INTEGER, printer_id INTEGER, user_id INTEGER NOT NULL,
                        operator_id INTEGER, idempotency_key VARCHAR(100), priority INTEGER DEFAULT 0,
                        status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', progress NUMERIC(5,2) DEFAULT 0,
                        started_at TIMESTAMP, completed_at TIMESTAMP, error_reason VARCHAR(255),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbc.update("INSERT INTO farm_user(id, username, password_hash) "
                    + "VALUES (9104, 'startup-operator', 'hash')");
            jdbc.update("INSERT INTO farm_print_file(id, user_id, original_name, safe_name) "
                    + "VALUES (9102, 9104, 'startup-legacy.gcode', 'startup-legacy.gcode')");
            jdbc.update("INSERT INTO farm_printer(id, name, machine_number, status, current_job_id) "
                    + "VALUES (9103, 'Startup Legacy Printer', 'STARTUP-01', 'PRINTING', 9101)");
            jdbc.update("INSERT INTO farm_print_job(id, file_id, printer_id, user_id, operator_id, "
                    + "idempotency_key, status, started_at) VALUES "
                    + "(9101, 9102, 9103, 9104, 9104, 'startup-legacy-key', 'PENDING', "
                    + "'2026-09-12 10:00:00')");
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建 Local SQLite 升级测试 fixture", exception);
        }
    }

    private static void runResource(JdbcTemplate jdbc, String resource) {
        new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(jdbc.getDataSource());
    }

    private java.util.List<String> indexNames() {
        return jdbcTemplate.queryForList("PRAGMA index_list(farm_print_job)").stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }
}
