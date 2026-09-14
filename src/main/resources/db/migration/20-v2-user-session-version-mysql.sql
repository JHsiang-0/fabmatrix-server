-- R100-03: invalidate existing sessions after password, role or account-state changes.
-- Repeatable for an existing MySQL data volume; back up the database before applying.

SET @farm_has_token_version = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_user'
      AND column_name = 'token_version'
);
SET @farm_add_token_version = IF(
    @farm_has_token_version = 0,
    'ALTER TABLE farm_user ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 COMMENT ''会话版本（密码、角色或账号状态变化时递增）'' AFTER enabled',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_token_version;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;

UPDATE farm_user SET token_version = 0 WHERE token_version IS NULL;
