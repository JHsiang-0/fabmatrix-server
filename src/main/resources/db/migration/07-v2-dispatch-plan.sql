-- v2 用户批量分配计划和逐项结果。
-- 可用于已有 MySQL 数据库；只新增表，不修改或删除历史任务。
-- 执行前仍建议备份 farm 数据库。脚本可重复执行。

CREATE TABLE IF NOT EXISTS farm_dispatch_plan (
    id VARCHAR(64) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    strategy VARCHAR(30) NOT NULL,
    action VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    confirmation_token_hash VARCHAR(128) NOT NULL,
    created_by BIGINT NOT NULL,
    expires_at DATETIME NOT NULL,
    confirmed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dispatch_plan_creator (created_by),
    KEY idx_dispatch_plan_status (status),
    KEY idx_dispatch_plan_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户批量分配计划';

CREATE TABLE IF NOT EXISTS farm_dispatch_plan_item (
    id VARCHAR(64) NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    file_id BIGINT NULL,
    printer_id BIGINT NULL,
    job_id BIGINT NULL,
    status VARCHAR(30) NOT NULL,
    reason_code VARCHAR(64) NULL,
    message VARCHAR(255) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dispatch_item_plan (plan_id),
    KEY idx_dispatch_item_file (file_id),
    KEY idx_dispatch_item_printer (printer_id),
    KEY idx_dispatch_item_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户批量分配计划明细';
