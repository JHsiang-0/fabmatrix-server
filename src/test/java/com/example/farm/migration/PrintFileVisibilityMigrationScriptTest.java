package com.example.farm.migration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class PrintFileVisibilityMigrationScriptTest {

    @Test
    void mysqlAndSqliteVisibilityScriptsDiagnoseNonFolderParentsAndUseRealNamespaces() {
        for (String resource : new String[]{
                "db/migration/15-v2-print-file-visibility-mysql.sql",
                "db/migration/15-v2-print-file-visibility-sqlite.sql"
        }) {
            String sql = read(resource);
            assertThat(sql).contains("parent.is_folder = 0");
            assertThat(sql).contains("WHERE is_folder = 1");
            assertThat(sql).contains("parent_id IS NULL AND UPPER(visibility) = 'PRIVATE'");
            assertThat(sql).contains("parent_id IS NULL THEN UPPER(visibility)");
        }
        assertThat(read("db/migration/15-v2-print-file-visibility-sqlite.sql"))
                .doesNotContain("ALTER TABLE farm_print_file ADD COLUMN");
    }

    @Test
    void defaultFolderMigrationExplicitlyExcludesPublicRootFiles() {
        String sql = read("db/migration/13-v2-print-file-default-folders.sql");
        assertThat(sql).contains("UPPER(COALESCE(f.visibility, 'PRIVATE')) = 'PRIVATE'");
        assertThat(sql).doesNotContain("WHERE f.parent_id IS NULL\n  AND f.is_folder = 0\n  AND f.user_id IS NOT NULL$$");
    }

    @Test
    void sqliteDataMigrationRunsTwiceAfterSchemaColumnIsPreparedAndKeepsPublicRoot() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:visibility-migration-test?mode=memory&cache=shared")) {
            connection.createStatement().execute("""
                    CREATE TABLE farm_print_file (
                        id INTEGER PRIMARY KEY,
                        parent_id INTEGER,
                        is_folder INTEGER NOT NULL,
                        is_default_folder INTEGER NOT NULL,
                        visibility VARCHAR(7) NOT NULL DEFAULT 'PRIVATE',
                        original_name VARCHAR(255),
                        safe_name VARCHAR(255),
                        user_id INTEGER
                    )
                    """);
            connection.createStatement().execute("""
                    INSERT INTO farm_print_file
                        (id, parent_id, is_folder, is_default_folder, visibility, original_name, safe_name, user_id)
                    VALUES
                        (1, NULL, 1, 0, 'PUBLIC', '公共根', 'public-root', 1),
                        (2, NULL, 0, 0, 'PUBLIC', 'public.gcode', 'public-file', 1),
                        (3, NULL, 1, 1, 'PRIVATE', '默认目录', 'private-root', 1),
                        (4, 3, 1, 0, 'PUBLIC', '历史子目录', 'legacy-child', 1)
                    """);

            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, false);
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/15-v2-print-file-visibility-sqlite.sql"));
            populator.execute(dataSource);
            populator.execute(dataSource);

            try (var result = connection.createStatement().executeQuery(
                    "SELECT parent_id, visibility FROM farm_print_file WHERE id = 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("parent_id")).isNull();
                assertThat(result.getString("visibility")).isEqualTo("PUBLIC");
            }
            try (var result = connection.createStatement().executeQuery(
                    "SELECT visibility FROM farm_print_file WHERE id = 4")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("PRIVATE");
            }
        }
    }

    private String read(String resource) {
        try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("迁移资源不存在: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
