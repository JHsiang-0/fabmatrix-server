-- v2 单任务创建幂等键。
-- 只新增可为空字段和同一用户范围内的唯一索引，不修改历史任务。
-- 已有数据卷执行前请先备份；本脚本可重复执行。

SET @farm_has_idempotency_key = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_print_job'
      AND column_name = 'idempotency_key'
);
SET @farm_add_idempotency_key = IF(
    @farm_has_idempotency_key = 0,
    'ALTER TABLE farm_print_job ADD COLUMN idempotency_key VARCHAR(100) NULL COMMENT ''客户端创建幂等键'' AFTER user_id',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_idempotency_key;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;

SET @farm_has_idempotency_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_print_job'
      AND index_name = 'uk_print_job_user_idempotency'
);
SET @farm_add_idempotency_index = IF(
    @farm_has_idempotency_index = 0,
    'ALTER TABLE farm_print_job ADD UNIQUE KEY uk_print_job_user_idempotency (user_id, idempotency_key)',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_idempotency_index;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;
