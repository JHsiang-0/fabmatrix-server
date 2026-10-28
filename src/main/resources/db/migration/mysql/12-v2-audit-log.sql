-- T205 操作日志增量迁移。仅新增审计表和索引，不删除或改写业务数据。
CREATE TABLE IF NOT EXISTS farm_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_id BIGINT NULL,
    actor_username VARCHAR(50) NULL,
    actor_role VARCHAR(20) NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) NULL,
    target_label VARCHAR(200) NULL,
    result VARCHAR(16) NOT NULL,
    error_code VARCHAR(32) NULL,
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    trace_id VARCHAR(64) NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_occurred_id (occurred_at, id),
    INDEX idx_audit_actor (actor_id),
    INDEX idx_audit_action (action),
    INDEX idx_audit_target (target_type, target_id),
    INDEX idx_audit_result (result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='Farm 结构化操作审计日志';
