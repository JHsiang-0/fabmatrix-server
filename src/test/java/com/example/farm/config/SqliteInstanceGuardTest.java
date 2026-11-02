package com.example.farm.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteInstanceGuardTest {
    @TempDir Path temp;

    @Test
    void rejectsSecondInstanceAndAllowsRestartAfterRelease() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("farm.db");
        try (var ignored = new SqliteInstanceGuard(url)) {
            assertThatThrownBy(() -> new SqliteInstanceGuard(url)).isInstanceOf(IllegalStateException.class);
        }
        assertThatCode(() -> { try (var ignored = new SqliteInstanceGuard(url)) { } }).doesNotThrowAnyException();
    }
}
