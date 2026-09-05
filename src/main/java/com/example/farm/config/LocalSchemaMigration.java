package com.example.farm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** 为已有 Local Edition SQLite 数据库补齐新增字段，重复启动安全。 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalSchemaMigration implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        addColumnIfMissing("farm_user", "enabled", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing("farm_dispatch_plan", "retry_key", "VARCHAR(128)");
        addColumnIfMissing("farm_dispatch_plan_item", "source_plan_id", "VARCHAR(64)");
        addColumnIfMissing("farm_dispatch_plan_item", "source_item_id", "VARCHAR(64)");
        addColumnIfMissing("farm_dispatch_plan_item", "recovery_action", "VARCHAR(64)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_dispatch_plan_created_retry_key "
                + "ON farm_dispatch_plan(created_by, retry_key)");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Set<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")")
                .stream()
                .map(row -> row.entrySet().stream()
                        .filter(entry -> "name".equalsIgnoreCase(entry.getKey()))
                        .map(entry -> String.valueOf(entry.getValue()))
                        .findFirst()
                        .orElse(""))
                .collect(Collectors.toSet());
        if (!columns.contains(column)) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("Local SQLite schema migration added column: {}.{}", table, column);
        }
    }
}
