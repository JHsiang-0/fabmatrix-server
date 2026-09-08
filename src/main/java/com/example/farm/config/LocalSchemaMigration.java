package com.example.farm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.List;
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
        createAuditLogTableIfMissing();
        addColumnIfMissing("farm_user", "enabled", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing("farm_dispatch_plan", "retry_key", "VARCHAR(128)");
        addColumnIfMissing("farm_dispatch_plan_item", "source_plan_id", "VARCHAR(64)");
        addColumnIfMissing("farm_dispatch_plan_item", "source_item_id", "VARCHAR(64)");
        addColumnIfMissing("farm_dispatch_plan_item", "recovery_action", "VARCHAR(64)");
        addPrinterMonitorColumnsIfMissing();
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_dispatch_plan_created_retry_key "
                + "ON farm_dispatch_plan(created_by, retry_key)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_printer_monitor_enabled "
                + "ON farm_printer(monitor_enabled, firmware_type)");
        migratePrintFileDefaultFolders();
    }

    private void addPrinterMonitorColumnsIfMissing() {
        addColumnIfMissing("farm_printer", "monitor_enabled", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("farm_printer", "connection_status", "VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'");
        addColumnIfMissing("farm_printer", "last_seen_at", "TIMESTAMP");
        addColumnIfMissing("farm_printer", "last_communication_error", "VARCHAR(255)");
        addColumnIfMissing("farm_printer", "consecutive_failure_count", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("farm_printer", "last_known_status", "VARCHAR(32)");
        addColumnIfMissing("farm_printer", "last_protocol_state", "VARCHAR(32)");
        addColumnIfMissing("farm_printer", "last_device_uptime", "INTEGER");
    }

    private void createAuditLogTableIfMissing() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS farm_audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    actor_id INTEGER,
                    actor_username VARCHAR(50),
                    actor_role VARCHAR(20),
                    action VARCHAR(64) NOT NULL,
                    target_type VARCHAR(32) NOT NULL,
                    target_id VARCHAR(64),
                    target_label VARCHAR(200),
                    result VARCHAR(16) NOT NULL,
                    error_code VARCHAR(32),
                    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    trace_id VARCHAR(64)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_audit_occurred_id "
                + "ON farm_audit_log(occurred_at, id)");
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

    private void migratePrintFileDefaultFolders() {
        addColumnIfMissing("farm_print_file", "is_default_folder", "INTEGER NOT NULL DEFAULT 0");

        List<Integer> duplicateUsers = jdbcTemplate.queryForList("""
                SELECT user_id
                FROM farm_print_file
                WHERE is_folder = 1 AND is_default_folder = 1 AND user_id IS NOT NULL
                GROUP BY user_id
                HAVING COUNT(*) > 1
                """, Integer.class);
        if (duplicateUsers.isEmpty()) {
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_print_file_default_folder_user "
                    + "ON farm_print_file(user_id) WHERE is_folder = 1 AND is_default_folder = 1");
        } else {
            log.warn("SQLite default folder uniqueness index skipped for duplicate users: {}", duplicateUsers);
        }

        List<Integer> userIds = jdbcTemplate.queryForList(
                "SELECT id FROM farm_user WHERE id IS NOT NULL", Integer.class);
        for (Integer userId : userIds) {
            String username = jdbcTemplate.queryForObject(
                    "SELECT username FROM farm_user WHERE id = ?", String.class, userId);
            String defaultFolderName = username + "的文件";
            List<Integer> defaults = jdbcTemplate.queryForList("""
                    SELECT id
                    FROM farm_print_file
                    WHERE user_id = ? AND is_folder = 1 AND is_default_folder = 1
                    ORDER BY id
                    """, Integer.class, userId);
            if (defaults.size() > 1) {
                log.warn("SQLite user has duplicate default folders; migration skipped: userId={}, folderIds={}",
                        userId, defaults);
                continue;
            }

            Integer defaultId = defaults.isEmpty() ? null : defaults.get(0);
            if (defaultId == null) {
                Integer sameNameCount = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM farm_print_file
                        WHERE user_id = ? AND parent_id IS NULL AND is_folder = 1
                          AND is_default_folder = 0 AND original_name = ?
                        """, Integer.class, userId, defaultFolderName);
                if (sameNameCount != null && sameNameCount > 0) {
                    log.warn("SQLite default folder skipped because root folder name conflicts: userId={}", userId);
                    continue;
                }
                jdbcTemplate.update("""
                        INSERT INTO farm_print_file
                            (parent_id, is_folder, is_default_folder, original_name, safe_name, user_id)
                        VALUES (NULL, 1, 1, ?, ?, ?)
                        """, defaultFolderName, "folder_default_" + userId, userId);
                defaultId = jdbcTemplate.queryForObject("""
                        SELECT id
                        FROM farm_print_file
                        WHERE user_id = ? AND is_folder = 1 AND is_default_folder = 1
                        ORDER BY id DESC LIMIT 1
                        """, Integer.class, userId);
            }

            if (defaultId != null) {
                jdbcTemplate.update("""
                        UPDATE farm_print_file
                        SET parent_id = ?
                        WHERE user_id = ? AND parent_id IS NULL AND is_folder = 0
                        """, defaultId, userId);
            }
        }
    }
}
