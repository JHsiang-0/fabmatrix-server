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
    void existingLocalSchemaMigrationBackfillsLegacyStatesAndIndexesOnce() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:print-job-query-local-schema?mode=memory&cache=shared")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/migration/local-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.update("INSERT INTO farm_print_job(id, user_id, status, created_at, updated_at) "
                    + "VALUES (7001, 1, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                    + "(7002, 1, 'MANUAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                    + "(7003, 1, 'CANCELED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            LocalSchemaMigration migration = new LocalSchemaMigration(jdbc);
            migration.run();
            migration.run();

            assertThat(jdbc.queryForList("SELECT status FROM farm_print_job WHERE id >= 7001 ORDER BY id",
                    String.class)).containsExactly("QUEUED", "QUEUED", "CANCELLED");
            assertThat(indexNames(jdbc)).contains("idx_print_job_history_status_completed_id",
                    "idx_print_job_active_status_created_id");
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

    private void makeLegacyPrinterTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE farm_printer_legacy AS SELECT * FROM farm_printer WHERE 0");
        jdbc.execute("DROP TABLE farm_printer");
        jdbc.execute("ALTER TABLE farm_printer_legacy RENAME TO farm_printer");
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
