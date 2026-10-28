-- Local Edition SQLite schema. 只创建业务表，不导入 Server Edition 的真实数据。
-- SQLite 的 INTEGER PRIMARY KEY 对应 MySQL 的 BIGINT AUTO_INCREMENT。

CREATE TABLE IF NOT EXISTS farm_user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'OPERATOR',
    enabled INTEGER NOT NULL DEFAULT 1,
    token_version INTEGER NOT NULL DEFAULT 0,
    must_change_password INTEGER NOT NULL DEFAULT 0,
    email VARCHAR(100), phone VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS farm_print_file (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id INTEGER, is_folder INTEGER NOT NULL DEFAULT 0,
    is_default_folder INTEGER NOT NULL DEFAULT 0,
    visibility VARCHAR(7) NOT NULL DEFAULT 'PRIVATE',
    rustfs_key VARCHAR(500), original_name VARCHAR(255) NOT NULL,
    safe_name VARCHAR(255) NOT NULL, file_url VARCHAR(500), file_size INTEGER,
    user_id INTEGER, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    est_time INTEGER, material_type VARCHAR(20), nozzle_size NUMERIC(3,2),
    thumbnail_url VARCHAR(500), filament_weight NUMERIC(10,2),
    filament_length NUMERIC(10,2), nozzle_temp INTEGER, bed_temp INTEGER,
    layer_height NUMERIC(10,2), first_layer_nozzle_temp INTEGER,
    first_layer_bed_temp INTEGER, first_layer_height NUMERIC(10,2)
);

CREATE TABLE IF NOT EXISTS farm_printer (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(50) NOT NULL, ip_address VARCHAR(50) UNIQUE,
    mac_address VARCHAR(50) UNIQUE, firmware_type VARCHAR(20) NOT NULL DEFAULT 'KLIPPER',
    api_key VARCHAR(255), status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    is_safe_to_print INTEGER NOT NULL DEFAULT 0, current_job_id INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    current_material VARCHAR(50), nozzle_size NUMERIC(3,2),
    grid_row INTEGER, grid_col INTEGER, machine_number VARCHAR(50),
    monitor_enabled INTEGER NOT NULL DEFAULT 0,
    connection_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    last_seen_at TIMESTAMP,
    last_communication_error VARCHAR(255),
    consecutive_failure_count INTEGER NOT NULL DEFAULT 0,
    last_known_status VARCHAR(32),
    last_protocol_state VARCHAR(32),
    last_device_uptime INTEGER,
    CHECK (
        (grid_row IS NULL AND grid_col IS NULL)
        OR (
            typeof(grid_row) = 'integer' AND typeof(grid_col) = 'integer'
            AND grid_row > 0 AND grid_col > 0
        )
    )
);

-- Durable Local Edition setup latch. It survives ordinary uninstall and user-row
-- cleanup; only the complete data purge removes it and permits a new install.
CREATE TABLE IF NOT EXISTS farm_setup_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    initialized INTEGER NOT NULL DEFAULT 0 CHECK (initialized IN (0, 1)),
    initialized_at TIMESTAMP,
    initialized_by INTEGER
);

INSERT OR IGNORE INTO farm_setup_state (id, initialized) VALUES (1, 0);

-- This table-level CHECK is part of the new-database schema. The partial
-- unique index and INSERT/UPDATE guards are owned by LocalSchemaMigration and
-- are created (or the project-owned triggers are rebuilt) only after its
-- read-only coordinate precheck. This keeps an existing database with
-- duplicate coordinates diagnosable before any guard DDL.

CREATE TABLE IF NOT EXISTS farm_print_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER, printer_id INTEGER, user_id INTEGER NOT NULL,
    operator_id INTEGER, idempotency_key VARCHAR(100),
    file_name_snapshot VARCHAR(255), printer_name_snapshot VARCHAR(50),
    machine_number_snapshot VARCHAR(50), creator_username_snapshot VARCHAR(50),
    operator_username_snapshot VARCHAR(50), retry_of_job_id INTEGER,
    root_job_id INTEGER, attempt_number INTEGER NOT NULL DEFAULT 1,
    priority INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', progress NUMERIC(5,2) DEFAULT 0,
    started_at TIMESTAMP, completed_at TIMESTAMP, error_reason VARCHAR(255),
    -- SQLite CURRENT_TIMESTAMP is UTC; print-job timestamps use fixed Asia/Shanghai wall-clock.
    created_at TIMESTAMP NOT NULL DEFAULT (datetime('now', '+8 hours')),
    updated_at TIMESTAMP NOT NULL DEFAULT (datetime('now', '+8 hours'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_print_job_user_idempotency
    ON farm_print_job(user_id, idempotency_key);

-- TC-007 indexes are created by LocalSchemaMigration after it adds the
-- evolution columns to an existing farm_print_job table. Keeping them out of
-- fresh bootstrap prevents an old database from failing before migration.

CREATE INDEX IF NOT EXISTS idx_print_job_history_status_completed_id
    ON farm_print_job(status, completed_at, id);

CREATE INDEX IF NOT EXISTS idx_print_job_active_status_created_id
    ON farm_print_job(status, created_at, id);

CREATE TABLE IF NOT EXISTS farm_printer_status_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT, printer_id INTEGER, status VARCHAR(20),
    raw_state VARCHAR(50), system_message VARCHAR(255), filename VARCHAR(255),
    task_source VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN', farm_job_id INTEGER,
    task_match_reason VARCHAR(64),
    progress NUMERIC(5,2), tool_temperature NUMERIC(10,2), tool_target NUMERIC(10,2),
    bed_temperature NUMERIC(10,2), bed_target NUMERIC(10,2), print_duration NUMERIC(10,2),
    total_duration NUMERIC(10,2), filament_used NUMERIC(10,2), recorded_at TIMESTAMP
);

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
);

CREATE INDEX IF NOT EXISTS idx_printer_execution_printer_state
    ON farm_printer_execution(printer_id, state, id);
CREATE INDEX IF NOT EXISTS idx_printer_execution_job
    ON farm_printer_execution(farm_job_id, state);
CREATE UNIQUE INDEX IF NOT EXISTS uk_printer_execution_active
    ON farm_printer_execution(printer_id) WHERE state = 'ACTIVE';

CREATE TABLE IF NOT EXISTS farm_dispatch_plan (
    id VARCHAR(64) PRIMARY KEY, mode VARCHAR(20) NOT NULL, strategy VARCHAR(30) NOT NULL,
    action VARCHAR(30) NOT NULL, status VARCHAR(30) NOT NULL, version INTEGER NOT NULL DEFAULT 1,
    retry_key VARCHAR(128),
    confirmation_token_hash VARCHAR(128) NOT NULL, created_by INTEGER NOT NULL,
    expires_at TIMESTAMP NOT NULL, confirmed_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS farm_dispatch_plan_item (
    id VARCHAR(64) PRIMARY KEY, plan_id VARCHAR(64) NOT NULL, file_id INTEGER,
    printer_id INTEGER, job_id INTEGER, resource_fingerprint VARCHAR(64), status VARCHAR(30) NOT NULL,
    source_plan_id VARCHAR(64), source_item_id VARCHAR(64), recovery_action VARCHAR(64),
    reason_code VARCHAR(64), message VARCHAR(255), attempt_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP, completed_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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
);

CREATE INDEX IF NOT EXISTS idx_audit_occurred_id ON farm_audit_log(occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON farm_audit_log(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON farm_audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_target ON farm_audit_log(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_result ON farm_audit_log(result);

-- 单农场登录会话；不存储 JWT 或密码。
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
);
CREATE INDEX IF NOT EXISTS idx_login_session_user ON farm_login_session(user_id, revoked_at, expires_at);
