package com.example.farm.local;

import com.example.farm.FarmApplication;
import com.zaxxer.hikari.HikariDataSource;
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

        var healthRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + serverPort + "/actuator/health"))
                .GET().build();
        var health = java.net.http.HttpClient.newHttpClient()
                .send(healthRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM farm_print_job", Integer.class))
                .isEqualTo(1);
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
        assertThat(indexNames()).contains("uk_print_job_retry_of_job", "uk_print_job_root_attempt");
        assertThat(jdbcTemplate.queryForList("PRAGMA table_info(farm_print_job)").stream()
                .map(column -> String.valueOf(column.get("name"))))
                .contains("file_name_snapshot", "printer_name_snapshot", "machine_number_snapshot",
                        "creator_username_snapshot", "operator_username_snapshot", "retry_of_job_id",
                        "root_job_id", "attempt_number");
    }

    private static void createPreTc007Fixture(Path databasePath) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath())) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, false));
            runResource(jdbc, "db/migration/local-schema.sql");
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
