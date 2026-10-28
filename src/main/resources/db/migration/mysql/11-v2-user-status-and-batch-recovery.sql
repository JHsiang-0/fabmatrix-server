-- T107: persistent user status and T104: batch recovery audit fields.
-- This migration is repeatable for an existing MySQL data volume.

SET @farm_has_user_enabled = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_user'
      AND column_name = 'enabled'
);
SET @farm_add_user_enabled = IF(
    @farm_has_user_enabled = 0,
    'ALTER TABLE farm_user ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''账号是否启用'' AFTER role',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_user_enabled;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;

UPDATE farm_user SET enabled = 1 WHERE enabled IS NULL;

SET @farm_has_retry_key = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dispatch_plan'
      AND column_name = 'retry_key'
);
SET @farm_add_retry_key = IF(
    @farm_has_retry_key = 0,
    'ALTER TABLE farm_dispatch_plan ADD COLUMN retry_key VARCHAR(128) NULL COMMENT ''批量失败项恢复幂等键''',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_retry_key;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;

SET @farm_has_retry_key_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dispatch_plan'
      AND index_name = 'uk_dispatch_plan_created_retry_key'
);
SET @farm_add_retry_key_index = IF(
    @farm_has_retry_key_index = 0,
    'ALTER TABLE farm_dispatch_plan ADD UNIQUE KEY uk_dispatch_plan_created_retry_key (created_by, retry_key)',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_retry_key_index;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;

SET @farm_has_source_plan_id = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dispatch_plan_item'
      AND column_name = 'source_plan_id'
);
SET @farm_add_source_plan_id = IF(
    @farm_has_source_plan_id = 0,
    'ALTER TABLE farm_dispatch_plan_item ADD COLUMN source_plan_id VARCHAR(64) NULL COMMENT ''恢复来源计划 ID''',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_source_plan_id;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;

SET @farm_has_source_item_id = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dispatch_plan_item'
      AND column_name = 'source_item_id'
);
SET @farm_add_source_item_id = IF(
    @farm_has_source_item_id = 0,
    'ALTER TABLE farm_dispatch_plan_item ADD COLUMN source_item_id VARCHAR(64) NULL COMMENT ''恢复来源计划明细 ID''',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_source_item_id;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;

SET @farm_has_recovery_action = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dispatch_plan_item'
      AND column_name = 'recovery_action'
);
SET @farm_add_recovery_action = IF(
    @farm_has_recovery_action = 0,
    'ALTER TABLE farm_dispatch_plan_item ADD COLUMN recovery_action VARCHAR(64) NULL COMMENT ''失败项恢复动作''',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_recovery_action;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;
