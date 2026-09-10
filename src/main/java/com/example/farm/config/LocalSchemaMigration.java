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
        addColumnIfMissing("farm_print_file", "visibility", "VARCHAR(7) NOT NULL DEFAULT 'PRIVATE'");
        addPrinterMonitorColumnsIfMissing();
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_dispatch_plan_created_retry_key "
                + "ON farm_dispatch_plan(created_by, retry_key)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_printer_monitor_enabled "
                + "ON farm_printer(monitor_enabled, firmware_type)");
        jdbcTemplate.update("UPDATE farm_print_file SET visibility = 'PRIVATE' "
                + "WHERE visibility IS NULL OR UPPER(visibility) NOT IN ('PRIVATE', 'PUBLIC')");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_print_file_visibility_parent "
                + "ON farm_print_file(visibility, parent_id, user_id)");
        migratePrintFileDefaultFolders();
        validatePrintFileStructure();
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
                            (parent_id, is_folder, is_default_folder, visibility, original_name, safe_name, user_id)
                        VALUES (NULL, 1, 1, 'PRIVATE', ?, ?, ?)
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

        // This is intentionally unconditional and therefore repeatable: a default
        // folder is always a private boundary, including all legacy descendants.
        jdbcTemplate.update("""
                WITH RECURSIVE default_tree(id, path, depth) AS (
                    SELECT id, ',' || id || ',', 1
                    FROM farm_print_file
                    WHERE is_folder = 1 AND is_default_folder = 1
                    UNION ALL
                    SELECT child.id, default_tree.path || child.id || ',', default_tree.depth + 1
                    FROM farm_print_file child
                    JOIN default_tree ON child.parent_id = default_tree.id
                    WHERE default_tree.depth < 1000
                      AND instr(default_tree.path, ',' || child.id || ',') = 0
                )
                UPDATE farm_print_file
                SET visibility = 'PRIVATE'
                WHERE id IN (SELECT id FROM default_tree)
                """);
    }

    private void validatePrintFileStructure() {
        int orphanCount = count("""
                SELECT COUNT(*)
                FROM farm_print_file child
                LEFT JOIN farm_print_file parent ON parent.id = child.parent_id
                WHERE child.parent_id IS NOT NULL AND parent.id IS NULL
                """);
        int nonFolderParentCount = count("""
                SELECT COUNT(*)
                FROM farm_print_file child
                JOIN farm_print_file parent ON parent.id = child.parent_id
                WHERE parent.is_folder = 0
                """);
        int cycleCount = count("""
                WITH RECURSIVE ancestor_walk(start_id, current_id, path, has_cycle, depth) AS (
                    SELECT id, id, ',' || id || ',', 0, 0
                    FROM farm_print_file
                    UNION ALL
                    SELECT walk.start_id, parent.id,
                           walk.path || parent.id || ',',
                           CASE WHEN instr(walk.path, ',' || parent.id || ',') > 0 THEN 1 ELSE 0 END,
                           walk.depth + 1
                    FROM ancestor_walk walk
                    JOIN farm_print_file current_node ON current_node.id = walk.current_id
                    JOIN farm_print_file parent ON parent.id = current_node.parent_id
                    WHERE walk.has_cycle = 0 AND walk.depth < 1000
                )
                SELECT COUNT(*) FROM ancestor_walk WHERE has_cycle = 1
                """);
        int deepFolderCount = count("""
                WITH RECURSIVE folder_tree(id, path, folder_level, is_folder) AS (
                    SELECT id, ',' || id || ',', 1, is_folder
                    FROM farm_print_file
                    WHERE is_folder = 1 AND parent_id IS NULL
                    UNION ALL
                    SELECT child.id, tree.path || child.id || ',',
                           tree.folder_level + CASE WHEN child.is_folder = 1 THEN 1 ELSE 0 END,
                           child.is_folder
                    FROM folder_tree tree
                    JOIN farm_print_file child ON child.parent_id = tree.id
                    WHERE tree.folder_level < 1000
                      AND instr(tree.path, ',' || child.id || ',') = 0
                )
                SELECT COUNT(*) FROM folder_tree
                WHERE folder_level > 2 AND is_folder = 1
                """);
        int duplicateFolderNameCount = count("""
                SELECT COUNT(*) FROM (
                    SELECT parent_id, original_name
                    FROM farm_print_file
                    WHERE is_folder = 1
                    GROUP BY parent_id, original_name
                    HAVING COUNT(*) > 1
                )
                """);
        int visibilityConflictCount = count("""
                SELECT COUNT(*)
                FROM farm_print_file child
                JOIN farm_print_file parent ON parent.id = child.parent_id
                WHERE UPPER(parent.visibility) <> UPPER(child.visibility)
                """);
        int duplicateDefaultCount = count("""
                SELECT COUNT(*) FROM (
                    SELECT user_id
                    FROM farm_print_file
                    WHERE is_folder = 1 AND is_default_folder = 1 AND user_id IS NOT NULL
                    GROUP BY user_id
                    HAVING COUNT(*) > 1
                )
                """);
        int publicCandidateCount = count("""
                WITH RECURSIVE public_tree(candidate_id, descendant_id, path, depth) AS (
                    SELECT id, id, ',' || id || ',', 0
                    FROM farm_print_file
                    WHERE UPPER(visibility) = 'PUBLIC'
                    UNION ALL
                    SELECT tree.candidate_id, child.id, tree.path || child.id || ',', tree.depth + 1
                    FROM public_tree tree
                    JOIN farm_print_file child ON child.parent_id = tree.descendant_id
                    WHERE tree.depth < 1000
                      AND instr(tree.path, ',' || child.id || ',') = 0
                )
                SELECT COUNT(*) FROM public_tree
                """);

        log.info("SQLite 文件目录迁移诊断: publicCandidates={}, orphans={}, cycles={}, "
                        + "deepFolders={}, duplicateFolderNames={}, visibilityConflicts={}, "
                        + "duplicateDefaults={}, nonFolderParents={}",
                publicCandidateCount, orphanCount, cycleCount, deepFolderCount,
                duplicateFolderNameCount, visibilityConflictCount, duplicateDefaultCount,
                nonFolderParentCount);
        if (orphanCount > 0 || nonFolderParentCount > 0 || cycleCount > 0 || deepFolderCount > 0
                || duplicateFolderNameCount > 0 || visibilityConflictCount > 0
                || duplicateDefaultCount > 0) {
            throw new IllegalStateException("SQLite 文件目录存在结构或可见性异常，已阻止启动");
        }
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
