package com.example.farm.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

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
    }

    @Test
    void defaultFolderMigrationExplicitlyExcludesPublicRootFiles() {
        String sql = read("db/migration/13-v2-print-file-default-folders.sql");
        assertThat(sql).contains("UPPER(COALESCE(f.visibility, 'PRIVATE')) = 'PRIVATE'");
        assertThat(sql).doesNotContain("WHERE f.parent_id IS NULL\n  AND f.is_folder = 0\n  AND f.user_id IS NOT NULL$$");
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
