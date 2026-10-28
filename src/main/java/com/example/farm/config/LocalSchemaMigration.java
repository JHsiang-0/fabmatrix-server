package com.example.farm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** 为已有 Local Edition SQLite 数据库补齐新增字段，重复启动安全。 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalSchemaMigration implements CommandLineRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String LEGACY_DEFAULT_AISLES_JSON =
            "[{\"id\":\"default-column-aisle\",\"axis\":\"COLUMN\",\"afterIndex\":6,\"label\":\"过道\",\"size\":100}]";
    private static final String DEFAULT_AISLES_JSON =
            "[{\"id\":\"default-column-aisle\",\"axis\":\"COLUMN\",\"afterIndex\":7,\"label\":\"过道\",\"size\":100}]";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        migrateFirstAdminSetupState();
        validateDashboardLayoutPreconditions();
        ensurePrinterCoordinateGuards();
        migrateDashboardLayout();
        correctLegacyDefaultDashboardLayout();
        createAuditLogTableIfMissing();
        createPrinterExecutionTableIfMissing();
        addColumnIfMissing("farm_user", "enabled", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing("farm_user", "token_version", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("farm_user", "must_change_password", "INTEGER NOT NULL DEFAULT 0");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS farm_login_session (
                    id VARCHAR(36) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    token_version BIGINT NOT NULL DEFAULT 0,
                    ip_address VARCHAR(64),
                    user_agent VARCHAR(256),
                    created_at TIMESTAMP NOT NULL,
                    last_seen_at TIMESTAMP NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    revoked_at TIMESTAMP NULL
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_login_session_user ON farm_login_session(user_id, revoked_at, expires_at)");
        addColumnIfMissing("farm_dispatch_plan", "retry_key", "VARCHAR(128)");
        addColumnIfMissing("farm_dispatch_plan_item", "source_plan_id", "VARCHAR(64)");
        addColumnIfMissing("farm_dispatch_plan_item", "source_item_id", "VARCHAR(64)");
        addColumnIfMissing("farm_dispatch_plan_item", "recovery_action", "VARCHAR(64)");
        addColumnIfMissing("farm_print_file", "visibility", "VARCHAR(7) NOT NULL DEFAULT 'PRIVATE'");
        addColumnIfMissing("farm_printer_status_history", "task_source",
                "VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'");
        addColumnIfMissing("farm_printer_status_history", "farm_job_id", "INTEGER");
        addColumnIfMissing("farm_printer_status_history", "task_match_reason", "VARCHAR(64)");
        addPrinterMonitorColumnsIfMissing();
        migratePrintJobQuerySemantics();
        migratePrintJobAuditModel();
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_dispatch_plan_created_retry_key "
                + "ON farm_dispatch_plan(created_by, retry_key)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_printer_monitor_enabled "
                + "ON farm_printer(monitor_enabled, firmware_type)");
        jdbcTemplate.update("UPDATE farm_print_file SET visibility = 'PRIVATE' "
                + "WHERE visibility IS NULL OR UPPER(visibility) NOT IN ('PRIVATE', 'PUBLIC')");
        jdbcTemplate.update("UPDATE farm_user SET token_version = 0 WHERE token_version IS NULL");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_print_file_visibility_parent "
                + "ON farm_print_file(visibility, parent_id, user_id)");
        migratePrintFileDefaultFolders();
        validatePrintFileStructure();
    }

    /** 持久化设备执行会话，避免 RRF 结束后清空 job 字段或服务重启导致终态证据丢失。 */
    private void createPrinterExecutionTableIfMissing() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS farm_printer_execution (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    printer_id INTEGER NOT NULL,
                    farm_job_id INTEGER,
                    source VARCHAR(16) NOT NULL,
                    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    expected_filename VARCHAR(255),
                    observed_filename VARCHAR(255),
                    has_seen_processing INTEGER NOT NULL DEFAULT 0,
                    has_seen_paused INTEGER NOT NULL DEFAULT 0,
                    idle_observation_count INTEGER NOT NULL DEFAULT 0,
                    last_raw_state VARCHAR(50),
                    max_progress NUMERIC(5,2),
                    max_file_position NUMERIC(20,2),
                    file_size NUMERIC(20,2),
                    device_uptime_at_start INTEGER,
                    device_uptime_last INTEGER,
                    started_at TIMESTAMP NOT NULL,
                    last_seen_at TIMESTAMP NOT NULL,
                    ended_at TIMESTAMP,
                    end_reason VARCHAR(255),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_printer_execution_printer_state "
                + "ON farm_printer_execution(printer_id, state, id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_printer_execution_job "
                + "ON farm_printer_execution(farm_job_id, state)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_printer_execution_active "
                + "ON farm_printer_execution(printer_id) WHERE state = 'ACTIVE'");
    }

    /** 首次引入持久化 setup 状态时，已有用户数据必须视为已初始化。 */
    private void migrateFirstAdminSetupState() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS farm_setup_state (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    initialized INTEGER NOT NULL DEFAULT 0 CHECK (initialized IN (0, 1)),
                    initialized_at TIMESTAMP,
                    initialized_by INTEGER
                )
                """);
        jdbcTemplate.update("INSERT OR IGNORE INTO farm_setup_state (id, initialized) VALUES (1, 0)");
        jdbcTemplate.update("UPDATE farm_setup_state SET initialized = 1, initialized_at = COALESCE(initialized_at, CURRENT_TIMESTAMP) "
                + "WHERE id = 1 AND initialized = 0 AND EXISTS (SELECT 1 FROM farm_user)");
    }

    /** TC-003 旧任务状态回填及查询索引；只做幂等迁移，不在 GET 查询中写库。 */
    private void migratePrintJobQuerySemantics() {
        jdbcTemplate.update("UPDATE farm_print_job SET status = 'QUEUED' "
                + "WHERE status IN ('PENDING', 'MANUAL')");
        jdbcTemplate.update("UPDATE farm_print_job SET status = 'CANCELLED' "
                + "WHERE status = 'CANCELED'");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_print_job_history_status_completed_id "
                + "ON farm_print_job(status, completed_at, id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_print_job_active_status_created_id "
                + "ON farm_print_job(status, created_at, id)");
    }

    /** TC-007 历史快照与重试链；SQLite 列补齐、回填和索引均可重复执行。 */
    private void migratePrintJobAuditModel() {
        addColumnIfMissing("farm_print_job", "file_name_snapshot", "VARCHAR(255)");
        addColumnIfMissing("farm_print_job", "printer_name_snapshot", "VARCHAR(50)");
        addColumnIfMissing("farm_print_job", "machine_number_snapshot", "VARCHAR(50)");
        addColumnIfMissing("farm_print_job", "creator_username_snapshot", "VARCHAR(50)");
        addColumnIfMissing("farm_print_job", "operator_username_snapshot", "VARCHAR(50)");
        addColumnIfMissing("farm_print_job", "retry_of_job_id", "INTEGER");
        addColumnIfMissing("farm_print_job", "root_job_id", "INTEGER");
        addColumnIfMissing("farm_print_job", "attempt_number", "INTEGER NOT NULL DEFAULT 1");

        jdbcTemplate.update("UPDATE farm_print_job SET root_job_id = id WHERE root_job_id IS NULL");
        jdbcTemplate.update("UPDATE farm_print_job SET attempt_number = 1 "
                + "WHERE attempt_number IS NULL OR attempt_number < 1");
        jdbcTemplate.update("""
                UPDATE farm_print_job
                SET file_name_snapshot = COALESCE(file_name_snapshot,
                        (SELECT original_name FROM farm_print_file f WHERE f.id = farm_print_job.file_id)),
                    printer_name_snapshot = COALESCE(printer_name_snapshot,
                        (SELECT name FROM farm_printer p WHERE p.id = farm_print_job.printer_id)),
                    machine_number_snapshot = COALESCE(machine_number_snapshot,
                        (SELECT machine_number FROM farm_printer p WHERE p.id = farm_print_job.printer_id)),
                    creator_username_snapshot = COALESCE(creator_username_snapshot,
                        (SELECT username FROM farm_user u WHERE u.id = farm_print_job.user_id)),
                    operator_username_snapshot = CASE
                        WHEN started_at IS NOT NULL THEN COALESCE(operator_username_snapshot,
                            (SELECT username FROM farm_user u WHERE u.id = farm_print_job.operator_id))
                        ELSE operator_username_snapshot
                    END
                WHERE file_name_snapshot IS NULL
                   OR printer_name_snapshot IS NULL
                   OR machine_number_snapshot IS NULL
                   OR creator_username_snapshot IS NULL
                   OR (started_at IS NOT NULL AND operator_username_snapshot IS NULL)
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_print_job_retry_of_job "
                + "ON farm_print_job(retry_of_job_id)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_print_job_root_attempt "
                + "ON farm_print_job(root_job_id, attempt_number)");
    }

    /**
     * SYNC-002.1 的坐标预检必须在布局表任何 DDL 或默认行写入前完成。
     * SQLite 允许动态类型，因此非 INTEGER 的值也必须显式阻断。
     */
    private void validateDashboardLayoutPreconditions() {
        List<Map<String, Object>> issues = jdbcTemplate.queryForList("""
                SELECT issueType, id, name, gridRow, gridCol
                FROM (
                    SELECT 'PARTIAL_COORDINATE' AS issueType,
                           p.id, p.name, p.grid_row AS gridRow, p.grid_col AS gridCol
                    FROM farm_printer p
                    WHERE (p.grid_row IS NULL) <> (p.grid_col IS NULL)
                    UNION ALL
                    SELECT 'DUPLICATE_SLOT' AS issueType,
                           p.id, p.name, p.grid_row AS gridRow, p.grid_col AS gridCol
                    FROM farm_printer p
                    JOIN (
                        SELECT grid_row, grid_col
                        FROM farm_printer
                        WHERE grid_row IS NOT NULL AND grid_col IS NOT NULL
                        GROUP BY grid_row, grid_col
                        HAVING COUNT(*) > 1
                    ) duplicate_slot
                      ON duplicate_slot.grid_row = p.grid_row
                     AND duplicate_slot.grid_col = p.grid_col
                    UNION ALL
                    SELECT 'NON_POSITIVE_COORDINATE' AS issueType,
                           p.id, p.name, p.grid_row AS gridRow, p.grid_col AS gridCol
                    FROM farm_printer p
                    WHERE typeof(p.grid_row) = 'integer'
                      AND typeof(p.grid_col) = 'integer'
                      AND (p.grid_row <= 0 OR p.grid_col <= 0)
                    UNION ALL
                    SELECT 'DEFAULT_LAYOUT_OUT_OF_BOUNDS' AS issueType,
                           p.id, p.name, p.grid_row AS gridRow, p.grid_col AS gridCol
                    FROM farm_printer p
                    WHERE typeof(p.grid_row) = 'integer'
                      AND typeof(p.grid_col) = 'integer'
                      AND (p.grid_row > 4 OR p.grid_col > 12)
                    UNION ALL
                    SELECT 'NON_INTEGER_COORDINATE' AS issueType,
                           p.id, p.name, p.grid_row AS gridRow, p.grid_col AS gridCol
                    FROM farm_printer p
                    WHERE (p.grid_row IS NOT NULL AND typeof(p.grid_row) <> 'integer')
                       OR (p.grid_col IS NOT NULL AND typeof(p.grid_col) <> 'integer')
                ) coordinate_issues
                ORDER BY issueType, id
                """);
        if (!issues.isEmpty()) {
            log.error("SQLite SYNC-002.1 坐标预检失败: {}", issues);
            throw new IllegalStateException(
                    "SYNC-002.1 blocked: farm_printer coordinate precheck found issues: " + issues);
        }

        validateExistingDashboardLayout();
        validateExistingPrinterCoordinateGuards();
    }

    private void validateExistingDashboardLayout() {
        List<Map<String, Object>> layoutObjects = jdbcTemplate.queryForList(
                "SELECT type FROM sqlite_master WHERE name = 'farm_dashboard_layout'");
        if (layoutObjects.isEmpty()) {
            return;
        }
        if (layoutObjects.size() != 1 || !"table".equals(layoutObjects.get(0).get("type"))) {
            throw new IllegalStateException(
                    "SYNC-002.1 blocked: existing farm_dashboard_layout has incompatible structure");
        }

        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "PRAGMA table_info(farm_dashboard_layout)");
        List<String> expectedNames = List.of(
                "id", "row_count", "column_count", "aisles_json",
                "version", "updated_by", "created_at", "updated_at");
        List<String> actualNames = columns.stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        boolean columnsValid = actualNames.size() == expectedNames.size()
                && actualNames.equals(expectedNames)
                && columnMatches(columns, "id", "INTEGER", 1, 1)
                && columnMatches(columns, "row_count", "INTEGER", 1, 0)
                && columnMatches(columns, "column_count", "INTEGER", 1, 0)
                && columnMatches(columns, "aisles_json", "TEXT", 1, 0)
                && columnMatches(columns, "version", "INTEGER", 1, 0)
                && columnMatches(columns, "updated_by", "INTEGER", 0, 0)
                && columnMatches(columns, "created_at", "TIMESTAMP", 1, 0)
                && columnMatches(columns, "updated_at", "TIMESTAMP", 1, 0);
        String tableSql = jdbcTemplate.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' "
                        + "AND name = 'farm_dashboard_layout'", String.class);
        String normalizedSql = tableSql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        boolean checksValid = normalizedSql.contains("check(id=1)")
                && normalizedSql.contains("check(typeof(row_count)='integer'androw_countbetween1and30)")
                && normalizedSql.contains("check(typeof(column_count)='integer'andcolumn_countbetween1and60)")
                && normalizedSql.contains("check(row_count*column_count<=1800)")
                && normalizedSql.contains("check(typeof(version)='integer'andversion>0)");
        if (!columnsValid || !checksValid) {
            throw new IllegalStateException(
                    "SYNC-002.1 blocked: existing farm_dashboard_layout has incompatible structure");
        }
    }

    private boolean columnMatches(List<Map<String, Object>> columns, String name,
                                  String type, int notNull, int primaryKey) {
        return columns.stream().anyMatch(column -> name.equals(column.get("name"))
                && type.equalsIgnoreCase(String.valueOf(column.get("type")))
                && Integer.valueOf(notNull).equals(column.get("notnull"))
                && Integer.valueOf(primaryKey).equals(column.get("pk")));
    }

    private void validateExistingPrinterCoordinateGuards() {
        validateExistingIndex("uk_farm_printer_grid_position", List.of("grid_row", "grid_col"));
        validateExistingTrigger("trg_farm_printer_grid_position_insert");
        validateExistingTrigger("trg_farm_printer_grid_position_update");
    }

    private void validateExistingIndex(String indexName, List<String> expectedColumns) {
        List<Map<String, Object>> namedObjects = jdbcTemplate.queryForList(
                "SELECT type FROM sqlite_master WHERE name = ?", indexName);
        if (namedObjects.size() > 1 || (namedObjects.size() == 1
                && !"index".equals(namedObjects.get(0).get("type")))) {
            throw new IllegalStateException(
                    "SYNC-002.1 blocked: existing SQLite index " + indexName
                            + " has incompatible structure");
        }
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("PRAGMA index_list(farm_printer)");
        Map<String, Object> index = indexes.stream()
                .filter(row -> indexName.equals(row.get("name")))
                .findFirst()
                .orElse(null);
        if (namedObjects.isEmpty() && index == null) {
            return;
        }
        if (index == null) {
            throw new IllegalStateException(
                    "SYNC-002.1 blocked: existing SQLite index " + indexName
                            + " has incompatible structure");
        }
        List<String> actualColumns = jdbcTemplate.queryForList(
                        "PRAGMA index_info(" + indexName + ")").stream()
                .sorted((left, right) -> Integer.compare(
                        ((Number) left.get("seqno")).intValue(),
                        ((Number) right.get("seqno")).intValue()))
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        boolean unique = index.get("unique") instanceof Number number && number.intValue() == 1;
        boolean partial = index.get("partial") instanceof Number number && number.intValue() == 1;
        String indexSql = jdbcTemplate.queryForObject("SELECT sql FROM sqlite_master "
                + "WHERE type = 'index' AND name = ?", String.class, indexName);
        String normalizedSql = normalizeSql(indexSql);
        String expectedSql = normalizeSql("CREATE UNIQUE INDEX " + indexName
                + " ON farm_printer(grid_row, grid_col) "
                + "WHERE grid_row IS NOT NULL AND grid_col IS NOT NULL");
        if (!unique || !partial || !actualColumns.equals(expectedColumns)
                || !normalizedSql.equals(expectedSql)) {
            throw new IllegalStateException(
                    "SYNC-002.1 blocked: existing SQLite index " + indexName + " has incompatible structure");
        }
    }

    private void validateExistingTrigger(String triggerName) {
        List<Map<String, Object>> namedObjects = jdbcTemplate.queryForList(
                "SELECT type, tbl_name FROM sqlite_master WHERE name = ?", triggerName);
        if (namedObjects.size() > 1 || (namedObjects.size() == 1
                && (!"trigger".equals(namedObjects.get(0).get("type"))
                || !"farm_printer".equals(namedObjects.get(0).get("tbl_name"))))) {
            throw new IllegalStateException(
                    "SYNC-002.1 blocked: existing SQLite trigger " + triggerName
                            + " has incompatible structure");
        }
    }

    private void ensurePrinterCoordinateGuards() {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_farm_printer_grid_position "
                + "ON farm_printer(grid_row, grid_col) "
                + "WHERE grid_row IS NOT NULL AND grid_col IS NOT NULL");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_farm_printer_grid_position_insert");
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_farm_printer_grid_position_insert
                BEFORE INSERT ON farm_printer
                WHEN NOT (
                    (NEW.grid_row IS NULL AND NEW.grid_col IS NULL)
                    OR (
                        typeof(NEW.grid_row) = 'integer' AND typeof(NEW.grid_col) = 'integer'
                        AND NEW.grid_row > 0 AND NEW.grid_col > 0
                    )
                )
                BEGIN
                    SELECT RAISE(ABORT, 'farm_printer grid coordinates must be a positive integer pair or both NULL');
                END
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_farm_printer_grid_position_update");
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_farm_printer_grid_position_update
                BEFORE UPDATE OF grid_row, grid_col ON farm_printer
                WHEN NOT (
                    (NEW.grid_row IS NULL AND NEW.grid_col IS NULL)
                    OR (
                        typeof(NEW.grid_row) = 'integer' AND typeof(NEW.grid_col) = 'integer'
                        AND NEW.grid_row > 0 AND NEW.grid_col > 0
                    )
                )
                BEGIN
                    SELECT RAISE(ABORT, 'farm_printer grid coordinates must be a positive integer pair or both NULL');
                END
                """);
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("[\\s`\\\"\\[\\]]+", "").toLowerCase(Locale.ROOT);
    }

    private void migrateDashboardLayout() {
        if (count("SELECT COUNT(*) FROM sqlite_master "
                + "WHERE type = 'table' AND name = 'farm_dashboard_layout'") == 0) {
            jdbcTemplate.execute("""
                    CREATE TABLE farm_dashboard_layout (
                        id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                        row_count INTEGER NOT NULL
                            CHECK (typeof(row_count) = 'integer' AND row_count BETWEEN 1 AND 30),
                        column_count INTEGER NOT NULL
                            CHECK (typeof(column_count) = 'integer' AND column_count BETWEEN 1 AND 60),
                        aisles_json TEXT NOT NULL,
                        version INTEGER NOT NULL
                            CHECK (typeof(version) = 'integer' AND version > 0),
                        updated_by INTEGER,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CHECK (row_count * column_count <= 1800)
                    )
                    """);
        }
        jdbcTemplate.update("""
                INSERT INTO farm_dashboard_layout
                    (id, row_count, column_count, aisles_json, version, updated_by)
                SELECT 1, 4, 12,
                       '[{"id":"default-column-aisle","axis":"COLUMN","afterIndex":7,"label":"过道","size":100}]',
                       1, NULL
                WHERE NOT EXISTS (
                    SELECT 1 FROM farm_dashboard_layout WHERE id = 1
                )
                """);
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
                          AND UPPER(COALESCE(visibility, 'PRIVATE')) = 'PRIVATE'
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

    /**
     * 仅纠正 SYNC-002 的历史单例默认值；用户编辑、升级版本或其他过道配置均不覆盖。
     */
    private void correctLegacyDefaultDashboardLayout() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT row_count, column_count, aisles_json, version, updated_by "
                        + "FROM farm_dashboard_layout WHERE id = 1");
        if (rows.size() != 1) {
            return;
        }
        Map<String, Object> row = rows.get(0);
        if (!Integer.valueOf(4).equals(numberValue(row.get("row_count")))
                || !Integer.valueOf(12).equals(numberValue(row.get("column_count")))
                || !Integer.valueOf(1).equals(numberValue(row.get("version")))
                || row.get("updated_by") != null
                || !jsonEquivalent(row.get("aisles_json"), LEGACY_DEFAULT_AISLES_JSON)) {
            return;
        }
        jdbcTemplate.update("UPDATE farm_dashboard_layout SET aisles_json = ? "
                        + "WHERE id = 1 AND row_count = 4 AND column_count = 12 "
                        + "AND version = 1 AND updated_by IS NULL",
                DEFAULT_AISLES_JSON);
        log.info("SQLite SYNC-002 corrected the legacy default COLUMN aisle boundary to 7");
    }

    private Integer numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private boolean jsonEquivalent(Object actual, String expected) {
        try {
            JsonNode actualTree = OBJECT_MAPPER.readTree(String.valueOf(actual));
            JsonNode expectedTree = OBJECT_MAPPER.readTree(expected);
            return expectedTree.equals(actualTree);
        } catch (Exception exception) {
            return false;
        }
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
                    SELECT
                        CASE WHEN parent_id IS NULL THEN 'ROOT' ELSE 'CHILD' END AS namespace_kind,
                        parent_id,
                        CASE
                            WHEN parent_id IS NULL AND UPPER(visibility) = 'PRIVATE' THEN user_id
                            ELSE NULL
                        END AS root_user_id,
                        CASE
                            WHEN parent_id IS NULL THEN UPPER(visibility)
                            ELSE NULL
                        END AS root_visibility,
                        original_name
                    FROM farm_print_file
                    WHERE is_folder = 1
                    GROUP BY namespace_kind, parent_id, root_user_id, root_visibility, original_name
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
