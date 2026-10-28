-- 执行前停止服务并备份；仅执行一次，已有账号不强制改密。
ALTER TABLE farm_user ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0;
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
CREATE INDEX idx_login_session_user ON farm_login_session(user_id, revoked_at, expires_at);
