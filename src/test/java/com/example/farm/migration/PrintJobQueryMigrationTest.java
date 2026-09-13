package com.example.farm.migration;

import com.example.farm.config.LocalSchemaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrintJobQueryMigrationTest {

    @Test
    void mysqlAndSqliteMigrationScriptsDeclareRepeatableStatusAndQueryIndexSteps() {
        String mysql = read("db/migration/18-v2-print-job-query-semantics-mysql.sql");
        String sqlite = read("db/migration/18-v2-print-job-query-semantics-sqlite.sql");

        assertThat(mysql).contains("status = 'QUEUED'", "status = 'CANCELLED'",
                "information_schema.statistics", "idx_print_job_history_status_completed_id",
                "idx_print_job_active_status_created_id");
        assertThat(sqlite).contains("status = 'QUEUED'", "status = 'CANCELLED'",
                "CREATE INDEX IF NOT EXISTS idx_print_job_history_status_completed_id",
                "CREATE INDEX IF NOT EXISTS idx_print_job_active_status_created_id");
    }

    @Test
    void auditModelMigrationDeclaresSnapshotsLineageAndNoSelfReference() {
        String mysql = read("db/migration/19-v2-print-job-audit-model-mysql.sql");
        String sqlite = read("db/migration/19-v2-print-job-audit-model-sqlite.sql");
        for (String migration : List.of(mysql, sqlite)) {
            assertThat(migration).contains("file_name_snapshot", "printer_name_snapshot",
                    "machine_number_snapshot", "creator_username_snapshot",
                    "operator_username_snapshot", "retry_of_job_id", "root_job_id",
                    "attempt_number", "uk_print_job_retry_of_job", "uk_print_job_root_attempt")
                    .doesNotContain("FOREIGN KEY", "REFERENCES farm_print_job");
        }
        assertThat(mysql).contains("VARCHAR(255)", "VARCHAR(50)", "NOT NULL DEFAULT 1",
                "information_schema.columns", "information_schema.statistics",
                "WHEN j.started_at IS NOT NULL");
        assertThat(sqlite).contains("CREATE UNIQUE INDEX IF NOT EXISTS",
                "COALESCE(file_name_snapshot", "WHERE root_job_id IS NULL",
                "WHEN started_at IS NOT NULL");
        String localMigration = readWorkspaceFile("src/main/java/com/example/farm/config/LocalSchemaMigration.java");
        assertThat(localMigration).contains("migratePrintJobAuditModel();",
                "addColumnIfMissing(\"farm_print_job\", \"file_name_snapshot\"",
                "uk_print_job_retry_of_job", "uk_print_job_root_attempt");
    }

    @Test
    void migrationEntrypointsAndFreshSchemasKeepStatusIndexAndLocalTimeContractsAligned() {
        String farmSchema = read("db/migration/farm.sql");
        String localSchema = read("db/migration/local-schema.sql");
        for (String schema : List.of(farmSchema, localSchema)) {
            assertThat(schema).contains("status", "DEFAULT 'QUEUED'",
                    "idx_print_job_history_status_completed_id",
                    "idx_print_job_active_status_created_id");
        }
        String userTable = localSchema.substring(localSchema.indexOf("CREATE TABLE IF NOT EXISTS farm_user"),
                localSchema.indexOf("CREATE TABLE IF NOT EXISTS farm_print_file"));
        int jobStart = localSchema.indexOf("CREATE TABLE IF NOT EXISTS farm_print_job");
        int jobEnd = localSchema.indexOf("CREATE TABLE IF NOT EXISTS farm_printer_status_history");
        String jobTable = localSchema.substring(jobStart, jobEnd);
        assertThat(localSchema.substring(0, jobStart)).doesNotContain("+8 hours");
        assertThat(localSchema.substring(jobEnd)).doesNotContain("+8 hours");
        assertThat(userTable).contains("created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP")
                .doesNotContain("+8 hours");
        assertThat(jobTable).contains("created_at TIMESTAMP NOT NULL DEFAULT (datetime('now', '+8 hours'))",
                "updated_at TIMESTAMP NOT NULL DEFAULT (datetime('now', '+8 hours'))");
        assertThat(jobTable).doesNotContain("uk_print_job_retry_of_job", "uk_print_job_root_attempt");
        assertThat(read("db/migration/18-v2-print-job-query-semantics-mysql.sql"))
                .contains("PREPARE farm_index_stmt", "DEALLOCATE PREPARE farm_index_stmt");
        assertThat(read("db/migration/18-v2-print-job-query-semantics-sqlite.sql"))
                .contains("CREATE INDEX IF NOT EXISTS");

        String migrationSource = readWorkspaceFile("src/main/java/com/example/farm/config/LocalSchemaMigration.java");
        assertThat(migrationSource).contains("migratePrintJobQuerySemantics();",
                "idx_print_job_history_status_completed_id",
                "idx_print_job_active_status_created_id");
        String mount = "18-v2-print-job-query-semantics-mysql.sql:/docker-entrypoint-initdb.d/18-v2-print-job-query-semantics-mysql.sql:ro";
        assertThat(readWorkspaceFile("docker-compose.yml")).contains(mount,
                "--default-time-zone=+08:00");
        assertThat(readWorkspaceFile("docker-compose.server.yml")).contains(mount,
                "TZ: Asia/Shanghai", "JAVA_TOOL_OPTIONS: -Duser.timezone=Asia/Shanghai",
                "--default-time-zone=+08:00");
        assertThat(readWorkspaceFile("src/main/resources/application.yaml"))
                .doesNotContain("time-zone:");
        assertThat(readWorkspaceFile("src/main/resources/application-local.yaml"))
                .doesNotContain("time-zone:");
        assertThat(readWorkspaceFile("src/main/resources/application-dev.yaml"))
                .contains("serverTimezone=Asia/Shanghai", "connection-init-sql: SET time_zone = '+08:00'");
        assertThat(readWorkspaceFile("src/main/resources/application-prod.yaml"))
                .contains("serverTimezone=Asia/Shanghai", "connection-init-sql: SET time_zone = '+08:00'")
                .doesNotContain("serverTimezone=Asia-Shanghai");
        assertThat(readWorkspaceFile("Dockerfile"))
                .contains("ENV TZ=Asia/Shanghai", "-Duser.timezone=Asia/Shanghai");
    }

    @Test
    void sqliteMigrationNormalizesLegacyStatesAndIsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:print-job-query-script?mode=memory&cache=shared")) {
            JdbcTemplate jdbc = jdbc(connection);
            jdbc.execute("CREATE TABLE farm_print_job (id INTEGER PRIMARY KEY, status VARCHAR(20), "
                    + "completed_at TIMESTAMP, created_at TIMESTAMP)");
            jdbc.update("INSERT INTO farm_print_job(id, status) VALUES (1, 'PENDING'), (2, 'MANUAL'), (3, 'CANCELED')");

            runResource(jdbc, "db/migration/18-v2-print-job-query-semantics-sqlite.sql");
            runResource(jdbc, "db/migration/18-v2-print-job-query-semantics-sqlite.sql");

            assertThat(jdbc.queryForList("SELECT status FROM farm_print_job ORDER BY id", String.class))
                    .containsExactly("QUEUED", "QUEUED", "CANCELLED");
            assertThat(indexNames(jdbc)).contains("idx_print_job_history_status_completed_id",
                    "idx_print_job_active_status_created_id");
        }
    }

    @Test
    void sqliteAuditModelBackfillsCurrentNamesAndIsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:print-job-audit-model-script?mode=memory&cache=shared")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/migration/local-schema.sql");
            jdbc.update("INSERT INTO farm_user(id, username, password_hash) VALUES (1, 'creator-now', 'hash'), "
                    + "(2, 'operator-now', 'hash')");
            jdbc.update("INSERT INTO farm_print_file(id, user_id, original_name, safe_name) "
                    + "VALUES (20, 1, 'renamed-now.gcode', 'safe.gcode')");
            jdbc.update("INSERT INTO farm_printer(id, name, machine_number) VALUES (403, 'Printer-now', 'M-03')");
            jdbc.update("INSERT INTO farm_print_job(id, file_id, printer_id, user_id, operator_id, status, "
                    + "root_job_id, attempt_number, started_at) "
                    + "VALUES (100, 20, 403, 1, 2, 'FAILED', NULL, 1, '2026-09-12 10:00:00'), "
                    + "(101, 20, 403, 1, 2, 'READY', NULL, 1, NULL)");

            runResource(jdbc, "db/migration/19-v2-print-job-audit-model-sqlite.sql");
            runResource(jdbc, "db/migration/19-v2-print-job-audit-model-sqlite.sql");

            Map<String, Object> row = jdbc.queryForMap("SELECT root_job_id, attempt_number, "
                    + "file_name_snapshot, printer_name_snapshot, machine_number_snapshot, "
                    + "creator_username_snapshot, operator_username_snapshot "
                    + "FROM farm_print_job WHERE id = 100");
            assertThat(((Number) row.get("root_job_id")).longValue()).isEqualTo(100L);
            assertThat(((Number) row.get("attempt_number")).intValue()).isEqualTo(1);
            assertThat(row).containsEntry("file_name_snapshot", "renamed-now.gcode")
                    .containsEntry("printer_name_snapshot", "Printer-now")
                    .containsEntry("machine_number_snapshot", "M-03")
                    .containsEntry("creator_username_snapshot", "creator-now")
                    .containsEntry("operator_username_snapshot", "operator-now");
            assertThat(jdbc.queryForObject(
                    "SELECT operator_username_snapshot FROM farm_print_job WHERE id = 101", String.class))
                    .isNull();
            assertThat(indexNames(jdbc)).contains("uk_print_job_retry_of_job", "uk_print_job_root_attempt");
        }
    }

    @Test
    void localBootstrapThenMigrationUpgradesPreTc007PrintJobWithoutLosingData() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:print-job-query-local-schema?mode=memory&cache=shared")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/migration/local-schema.sql");
            replaceWithPreTc007PrintJobTable(jdbc);
            jdbc.update("INSERT INTO farm_user(id, username, password_hash) "
                    + "VALUES (91, 'local-operator', 'hash')");
            jdbc.update("INSERT INTO farm_print_file(id, user_id, original_name, safe_name) "
                    + "VALUES (7102, 91, 'legacy-part.gcode', 'legacy-part.gcode')");
            jdbc.update("INSERT INTO farm_printer(id, name, machine_number, status, current_job_id) "
                    + "VALUES (7103, 'Legacy Printer', 'LEGACY-01', 'PRINTING', 7101)");
            jdbc.update("INSERT INTO farm_print_job(id, file_id, printer_id, user_id, operator_id, "
                    + "idempotency_key, status, progress, started_at, created_at, updated_at) "
                    + "VALUES (7101, 7102, 7103, 91, 91, 'legacy-idempotency-key', 'PENDING', "
                    + "37.5, '2026-09-12 10:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            // This is the Spring sql.init bootstrap step against an existing database.
            // Before T16.17 the TC-007 indexes fail here because the legacy table has no
            // retry_of_job_id/root_job_id columns.
            runResource(jdbc, "db/migration/local-schema.sql");

            LocalSchemaMigration migration = new LocalSchemaMigration(jdbc);
            migration.run();
            migration.run();

            Map<String, Object> row = jdbc.queryForMap("SELECT file_id, printer_id, user_id, operator_id, "
                    + "idempotency_key, status, progress, root_job_id, attempt_number, "
                    + "file_name_snapshot, printer_name_snapshot, machine_number_snapshot, "
                    + "creator_username_snapshot, operator_username_snapshot "
                    + "FROM farm_print_job WHERE id = 7101");
            assertThat(row).containsEntry("file_id", 7102)
                    .containsEntry("printer_id", 7103)
                    .containsEntry("user_id", 91)
                    .containsEntry("operator_id", 91)
                    .containsEntry("idempotency_key", "legacy-idempotency-key")
                    .containsEntry("status", "QUEUED")
                    .containsEntry("progress", 37.5)
                    .containsEntry("root_job_id", 7101)
                    .containsEntry("attempt_number", 1)
                    .containsEntry("file_name_snapshot", "legacy-part.gcode")
                    .containsEntry("printer_name_snapshot", "Legacy Printer")
                    .containsEntry("machine_number_snapshot", "LEGACY-01")
                    .containsEntry("creator_username_snapshot", "local-operator")
                    .containsEntry("operator_username_snapshot", "local-operator");
            assertThat(jdbc.queryForObject("SELECT current_job_id FROM farm_printer WHERE id = 7103",
                    Integer.class)).isEqualTo(7101);
            assertThat(indexNames(jdbc)).contains("uk_print_job_retry_of_job", "uk_print_job_root_attempt",
                    "idx_print_job_history_status_completed_id", "idx_print_job_active_status_created_id");
            assertThat(jdbc.queryForList("PRAGMA table_info(farm_print_job)").stream()
                    .map(column -> String.valueOf(column.get("name"))))
                    .contains("file_name_snapshot", "printer_name_snapshot", "machine_number_snapshot",
                            "creator_username_snapshot", "operator_username_snapshot", "retry_of_job_id",
                            "root_job_id", "attempt_number");
        }
    }

    @Test
    void freshSqliteUsesLocalWallClockOnlyForPrintJobDefaults() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:print-job-query-fresh-schema?mode=memory&cache=shared")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/migration/local-schema.sql");
            jdbc.update("INSERT INTO farm_user(username, password_hash) VALUES ('fresh-user', 'hash')");
            jdbc.update("INSERT INTO farm_print_job(user_id, status) VALUES (1, 'QUEUED')");

            Map<String, Object> offsets = jdbc.queryForMap(
                    "SELECT "
                            + "CAST(strftime('%s', (SELECT created_at FROM farm_user WHERE id = 1)) "
                            + "- strftime('%s', 'now') AS INTEGER) AS user_offset, "
                            + "CAST(strftime('%s', (SELECT created_at FROM farm_print_job WHERE id = 1)) "
                            + "- strftime('%s', 'now') AS INTEGER) AS job_offset");
            assertThat(((Number) offsets.get("user_offset")).longValue()).isBetween(-5L, 5L);
            assertThat(((Number) offsets.get("job_offset")).longValue()).isBetween(28795L, 28805L);
        }
    }

    private List<String> indexNames(JdbcTemplate jdbc) {
        return jdbc.queryForList("PRAGMA index_list(farm_print_job)").stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }

    private void runResource(JdbcTemplate jdbc, String resource) {
        new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(jdbc.getDataSource());
    }

    private JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, false));
    }

    private String readWorkspaceFile(String resource) {
        try {
            return Files.readString(Path.of(System.getProperty("user.dir"))
                    .resolve(resource).normalize());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void replaceWithPreTc007PrintJobTable(JdbcTemplate jdbc) {
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
    }

    private String read(String resource) {
        try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("迁移资源不存在: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
