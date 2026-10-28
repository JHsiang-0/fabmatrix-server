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
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardLayoutMigrationTest {

    @Test
    void precheckScriptsAreReadOnlyAndExposeStableIssueColumns() {
        for (String resource : List.of(
                "db/migration/mysql/dashboard-layout-precheck-mysql.sql",
                "db/migration/sqlite/dashboard-layout-precheck-sqlite.sql")) {
            String sql = stripComments(read(resource)).toUpperCase();
            assertThat(sql).contains("ISSUETYPE", "GRIDROW", "GRIDCOL", "ORDER BY ISSUETYPE, ID");
            assertThat(sql).doesNotContain("CREATE ", "ALTER ", "INSERT ", "UPDATE ", "DELETE ",
                    "DROP ", "TRIGGER ", "CALL ");
        }
        assertThat(read("db/migration/mysql/dashboard-layout-precheck-mysql.sql"))
                .contains("PARTIAL_COORDINATE", "DUPLICATE_SLOT", "NON_POSITIVE_COORDINATE",
                        "DEFAULT_LAYOUT_OUT_OF_BOUNDS");
        assertThat(read("db/migration/sqlite/dashboard-layout-precheck-sqlite.sql"))
                .contains("NON_INTEGER_COORDINATE", "typeof(p.grid_row)");
    }

    @Test
    void mysqlScriptsAndComposeKeepMigrationContractStatic() {
        String migration = read("db/migration/mysql/16-v2-dashboard-layout-mysql.sql");
        assertThat(migration).contains("DROP PROCEDURE IF EXISTS farm_dashboard_layout_migrate$$",
                "farm_dashboard_layout_migration_abort", "farm_dashboard_layout",
                "ck_farm_dashboard_layout_singleton", "row_count BETWEEN 1 AND 30",
                "column_count BETWEEN 1 AND 60", "row_count * column_count <= 1800",
                "version > 0", "ON DUPLICATE KEY UPDATE");
        assertThat(migration).doesNotContain("SIGNAL SQLSTATE");
        assertThat(migration).doesNotContain("UPDATE farm_printer", "machine_number");
        assertThat(read("db/archive/farm.sql")).contains("ck_farm_printer_grid_pair",
                "ck_farm_printer_grid_positive", "uk_farm_printer_grid_position",
                "grid_row", "grid_col")
                .doesNotContain("farm_dashboard_layout");
        assertThat(read("db/migration/mysql/02-current-schema.sql")).contains("ck_farm_printer_grid_pair",
                "ck_farm_printer_grid_positive", "uk_farm_printer_grid_position",
                "grid_row", "grid_col")
                .doesNotContain("farm_dashboard_layout", "CREATE TABLE farm_dashboard_layout",
                        "INSERT INTO farm_dashboard_layout");
        for (String compose : List.of("docker-compose.yml", "docker-compose.server.yml")) {
            String yaml = readWorkspaceFile(compose);
            assertThat(yaml).contains("db/init/mysql-schema.sql:/docker-entrypoint-initdb.d/01-mysql-schema.sql:ro")
                    .doesNotContain("db/migration/");
        }
    }

    @Test
    void sqliteCorrectsOnlyTheLegacyDefaultAndPreservesCorrectOrCustomizedLayouts() throws Exception {
        try (Connection connection = connection("default-aisle-correction")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            LocalSchemaMigration migration = new LocalSchemaMigration(jdbc);

            migration.run();
            jdbc.update("UPDATE farm_dashboard_layout SET aisles_json = ? WHERE id = 1",
                    "[{\"id\":\"default-column-aisle\",\"axis\":\"COLUMN\","
                            + "\"afterIndex\":6,\"label\":\"过道\",\"size\":100}]");
            migration.run();
            assertThat(jdbc.queryForObject("SELECT aisles_json FROM farm_dashboard_layout WHERE id = 1",
                    String.class)).contains("\"afterIndex\":7");

            jdbc.update("UPDATE farm_dashboard_layout SET aisles_json = ?, updated_by = 9 WHERE id = 1",
                    "[{\"id\":\"custom\",\"axis\":\"COLUMN\",\"afterIndex\":6,"
                            + "\"label\":\"用户过道\",\"size\":120}]");
            migration.run();
            assertThat(jdbc.queryForObject("SELECT aisles_json FROM farm_dashboard_layout WHERE id = 1",
                    String.class)).contains("\"afterIndex\":6", "用户过道");
        }
    }

    @Test
    void mysqlMigrationScopesInformationSchemaQueriesToActualTables() {
        String migration = stripComments(read("db/migration/mysql/16-v2-dashboard-layout-mysql.sql"))
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertThat(migration).doesNotContain("cc.table_name");
        assertThat(migration).contains(
                "on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name",
                "on tc.constraint_schema = cc.constraint_schema and tc.constraint_name = cc.constraint_name",
                "where tc.table_schema = database() and tc.table_name = 'farm_dashboard_layout'",
                "where tc.table_schema = database() and tc.table_name = 'farm_printer'");
        assertThat(countOccurrences(migration,
                "from information_schema.statistics where table_schema = database() "
                        + "and table_name = 'farm_printer' "
                        + "and index_name = 'uk_farm_printer_grid_position'"))
                .isEqualTo(2);
    }

    @Test
    void newSqliteSchemaAndLocalMigrationAreRepeatable() throws Exception {
        try (Connection connection = connection("new-schema")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            LocalSchemaMigration migration = new LocalSchemaMigration(jdbc);

            migration.run();
            migration.run();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_dashboard_layout", Integer.class))
                    .isEqualTo(1);
            Map<String, Object> layout = jdbc.queryForMap("SELECT * FROM farm_dashboard_layout WHERE id = 1");
            assertThat(layout).containsEntry("row_count", 4)
                    .containsEntry("column_count", 12)
                    .containsEntry("version", 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master "
                    + "WHERE type = 'index' AND name = 'uk_farm_printer_grid_position'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master "
                    + "WHERE type = 'trigger' AND name LIKE 'trg_farm_printer_grid_position_%'", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void existingValidSqliteDatabaseMigrationIsRepeatable() throws Exception {
        try (Connection connection = connection("existing-valid")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.update("INSERT INTO farm_printer (id, name, grid_row, grid_col) "
                    + "VALUES (11, 'legacy-valid', 4, 12)");
            LocalSchemaMigration migration = new LocalSchemaMigration(jdbc);

            migration.run();
            migration.run();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_dashboard_layout", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT grid_row FROM farm_printer WHERE id = 11", Integer.class))
                    .isEqualTo(4);
        }
    }

    @Test
    void existingSqliteAnomaliesBlockBeforeLayoutWriteAndPreserveCoordinates() throws Exception {
        try (Connection connection = connection("anomalies")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.update("INSERT INTO farm_printer (id, name, grid_row, grid_col) VALUES "
                    + "(1, 'partial', 1, NULL), (2, 'duplicate-a', 2, 2), "
                    + "(3, 'duplicate-b', 2, 2), (4, 'non-positive', 0, 3), "
                    + "(5, 'overflow', 5, 1), (6, 'text', 'x', 1)");

            LocalSchemaMigration migration = new LocalSchemaMigration(jdbc);
            assertThatThrownBy(migration::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("coordinate precheck");

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master "
                    + "WHERE type = 'table' AND name = 'farm_dashboard_layout'", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForList("SELECT id, grid_row, grid_col FROM farm_printer ORDER BY id"))
                    .extracting(row -> java.util.Arrays.asList(
                            row.get("id"), row.get("grid_row"), row.get("grid_col")))
                    .containsExactly(
                            java.util.Arrays.asList(1, 1, null), List.of(2, 2, 2),
                            List.of(3, 2, 2), List.of(4, 0, 3), List.of(5, 5, 1),
                            java.util.Arrays.asList(6, "x", 1));
        }
    }

    @Test
    void existingWrongNamedLayoutTableIsRejected() throws Exception {
        try (Connection connection = connection("wrong-layout")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            jdbc.execute("CREATE TABLE farm_dashboard_layout (id INTEGER PRIMARY KEY, row_count INTEGER)");
            LocalSchemaMigration migration = new LocalSchemaMigration(jdbc);

            assertThatThrownBy(migration::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("incompatible structure");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_dashboard_layout", Integer.class))
                    .isZero();
        }
    }

    @Test
    void sqliteCoordinateGuardsRejectHalfEmptyNonPositiveAndDuplicateSlots() throws Exception {
        try (Connection connection = connection("constraints")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            new LocalSchemaMigration(jdbc).run();
            jdbc.update("INSERT INTO farm_printer (id, name, grid_row, grid_col) VALUES (1, 'valid', 1, 1)");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO farm_printer (id, name, grid_row, grid_col) "
                    + "VALUES (2, 'half', 1, NULL)")).hasMessageContaining("grid");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO farm_printer (id, name, grid_row, grid_col) "
                    + "VALUES (3, 'non-positive', 0, 2)")).hasMessageContaining("grid");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO farm_printer (id, name, grid_row, grid_col) "
                    + "VALUES (4, 'duplicate', 1, 1)")).hasMessageContaining("UNIQUE");
        }
    }

    @Test
    void sqliteWrongIndexAndSameNamedPseudoObjectsAreRejected() throws Exception {
        try (Connection connection = connection("wrong-index")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.execute("CREATE INDEX uk_farm_printer_grid_position "
                    + "ON farm_printer(grid_row, grid_col)");

            assertThatThrownBy(() -> new LocalSchemaMigration(jdbc).run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("existing SQLite index");
        }

        try (Connection connection = connection("index-pseudo-object")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.execute("CREATE TABLE uk_farm_printer_grid_position (id INTEGER)");

            assertThatThrownBy(() -> new LocalSchemaMigration(jdbc).run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("existing SQLite index");
        }

        try (Connection connection = connection("trigger-pseudo-object")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.execute("CREATE TABLE trg_farm_printer_grid_position_insert (id INTEGER)");

            assertThatThrownBy(() -> new LocalSchemaMigration(jdbc).run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("existing SQLite trigger");
        }
    }

    @Test
    void sqliteProjectOwnedWrongTriggersAreRebuiltAfterPrecheck() throws Exception {
        try (Connection connection = connection("wrong-trigger")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.execute("""
                    CREATE TRIGGER trg_farm_printer_grid_position_insert
                    BEFORE INSERT ON farm_printer
                    WHEN 1
                    BEGIN
                        SELECT RAISE(IGNORE);
                    END
                    """);

            new LocalSchemaMigration(jdbc).run();

            String triggerSql = jdbc.queryForObject("SELECT sql FROM sqlite_master "
                    + "WHERE type = 'trigger' AND name = ?", String.class,
                    "trg_farm_printer_grid_position_insert");
            assertThat(triggerSql.replaceAll("\\s+", "").toLowerCase())
                    .contains("whennot((new.grid_rowisnullandnew.grid_colisnull)or")
                    .contains("raise(abort,'farm_printergridcoordinatesmustbeapositiveintegerpairorbothnull')");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO farm_printer "
                    + "(id, name, grid_row, grid_col) VALUES (99, 'invalid', 1, NULL)"))
                    .hasMessageContaining("grid coordinates");
        }
    }

    @Test
    void sqliteSameNamedInsertTriggerOnOtherTableIsRejectedAndPreserved() throws Exception {
        try (Connection connection = connection("insert-trigger-other-table")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.execute("CREATE TABLE other_trigger_table (id INTEGER PRIMARY KEY)");
            jdbc.execute("""
                    CREATE TRIGGER trg_farm_printer_grid_position_insert
                    BEFORE INSERT ON other_trigger_table
                    BEGIN
                        SELECT RAISE(IGNORE);
                    END
                    """);

            assertThatThrownBy(() -> new LocalSchemaMigration(jdbc).run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("existing SQLite trigger");
            assertThat(jdbc.queryForMap("SELECT type, tbl_name FROM sqlite_master "
                    + "WHERE name = ?", "trg_farm_printer_grid_position_insert"))
                    .containsEntry("type", "trigger")
                    .containsEntry("tbl_name", "other_trigger_table");
            jdbc.update("INSERT INTO other_trigger_table (id) VALUES (1)");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM other_trigger_table", Integer.class))
                    .isZero();
        }
    }

    @Test
    void sqliteSameNamedUpdateTriggerOnOtherTableIsRejectedAndPreserved() throws Exception {
        try (Connection connection = connection("update-trigger-other-table")) {
            JdbcTemplate jdbc = jdbc(connection);
            runResource(jdbc, "db/init/sqlite-schema.sql");
            makeLegacyPrinterTable(jdbc);
            jdbc.execute("CREATE TABLE other_trigger_table (id INTEGER PRIMARY KEY, value TEXT)");
            jdbc.update("INSERT INTO other_trigger_table (id, value) VALUES (1, 'before')");
            jdbc.execute("""
                    CREATE TRIGGER trg_farm_printer_grid_position_update
                    BEFORE UPDATE OF value ON other_trigger_table
                    BEGIN
                        SELECT RAISE(IGNORE);
                    END
                    """);

            assertThatThrownBy(() -> new LocalSchemaMigration(jdbc).run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("existing SQLite trigger");
            assertThat(jdbc.queryForMap("SELECT type, tbl_name FROM sqlite_master "
                    + "WHERE name = ?", "trg_farm_printer_grid_position_update"))
                    .containsEntry("type", "trigger")
                    .containsEntry("tbl_name", "other_trigger_table");
            jdbc.update("UPDATE other_trigger_table SET value = 'after' WHERE id = 1");
            assertThat(jdbc.queryForObject("SELECT value FROM other_trigger_table WHERE id = 1", String.class))
                    .isEqualTo("before");
        }
    }

    private Connection connection(String name) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:file:sync-002-1-" + name
                + "?mode=memory&cache=shared");
    }

    private JdbcTemplate jdbc(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, false));
    }

    private void runResource(JdbcTemplate jdbc, String resource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(resource));
        populator.execute(jdbc.getDataSource());
    }

    private void makeLegacyPrinterTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE farm_printer_legacy AS SELECT * FROM farm_printer WHERE 0");
        jdbc.execute("DROP TABLE farm_printer");
        jdbc.execute("ALTER TABLE farm_printer_legacy RENAME TO farm_printer");
    }

    private String read(String resource) {
        return readBytes(getClass().getClassLoader().getResourceAsStream(resource), resource);
    }

    private String readWorkspaceFile(String resource) {
        try {
            Path projectRoot = Path.of(System.getProperty("user.dir"));
            return Files.readString(projectRoot.resolve(resource).normalize());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String readBytes(java.io.InputStream stream, String resource) {
        if (stream == null) {
            throw new IllegalStateException("迁移资源不存在: " + resource);
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String stripComments(String sql) {
        return sql.replaceAll("(?s)--.*?(\\R|$)", "")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private int countOccurrences(String value, String searched) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(searched, offset)) >= 0) {
            count++;
            offset += searched.length();
        }
        return count;
    }
}
