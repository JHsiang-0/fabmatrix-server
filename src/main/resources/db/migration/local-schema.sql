-- Local Edition SQLite schema. 只创建业务表，不导入 Server Edition 的真实数据。
-- SQLite 的 INTEGER PRIMARY KEY 对应 MySQL 的 BIGINT AUTO_INCREMENT。

CREATE TABLE IF NOT EXISTS farm_user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'OPERATOR',
    email VARCHAR(100), phone VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS farm_print_file (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id INTEGER, is_folder INTEGER NOT NULL DEFAULT 0,
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
    grid_row INTEGER, grid_col INTEGER, machine_number VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS farm_print_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER, printer_id INTEGER, user_id INTEGER NOT NULL,
    operator_id INTEGER, idempotency_key VARCHAR(100), priority INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', progress NUMERIC(5,2) DEFAULT 0,
    started_at TIMESTAMP, completed_at TIMESTAMP, error_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_print_job_user_idempotency
    ON farm_print_job(user_id, idempotency_key);

CREATE TABLE IF NOT EXISTS farm_printer_status_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT, printer_id INTEGER, status VARCHAR(20),
    raw_state VARCHAR(50), system_message VARCHAR(255), filename VARCHAR(255),
    progress NUMERIC(5,2), tool_temperature NUMERIC(10,2), tool_target NUMERIC(10,2),
    bed_temperature NUMERIC(10,2), bed_target NUMERIC(10,2), print_duration NUMERIC(10,2),
    total_duration NUMERIC(10,2), filament_used NUMERIC(10,2), recorded_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS farm_dispatch_plan (
    id VARCHAR(64) PRIMARY KEY, mode VARCHAR(20) NOT NULL, strategy VARCHAR(30) NOT NULL,
    action VARCHAR(30) NOT NULL, status VARCHAR(30) NOT NULL, version INTEGER NOT NULL DEFAULT 1,
    confirmation_token_hash VARCHAR(128) NOT NULL, created_by INTEGER NOT NULL,
    expires_at TIMESTAMP NOT NULL, confirmed_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS farm_dispatch_plan_item (
    id VARCHAR(64) PRIMARY KEY, plan_id VARCHAR(64) NOT NULL, file_id INTEGER,
    printer_id INTEGER, job_id INTEGER, resource_fingerprint VARCHAR(64), status VARCHAR(30) NOT NULL,
    reason_code VARCHAR(64), message VARCHAR(255), attempt_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP, completed_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
