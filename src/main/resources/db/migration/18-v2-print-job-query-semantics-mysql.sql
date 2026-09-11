-- TC-003：任务查询状态回填及分页查询索引。
-- 无 Flyway；已有 MySQL 数据卷升级前备份，并按 OPERATIONS.md 手工执行。
-- 所有 UPDATE/索引创建均可重复执行。
-- DATETIME 查询不做 UTC 转换；服务端/JDBC/容器约定为 Asia/Shanghai wall-clock。

UPDATE farm_print_job
SET status = 'QUEUED'
WHERE status IN ('PENDING', 'MANUAL');

UPDATE farm_print_job
SET status = 'CANCELLED'
WHERE status = 'CANCELED';

SET @farm_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_print_job'
      AND index_name = 'idx_print_job_history_status_completed_id'
);
SET @farm_index_ddl = IF(
    @farm_index_exists = 0,
    'ALTER TABLE farm_print_job ADD INDEX idx_print_job_history_status_completed_id (status, completed_at, id)',
    'SELECT 1'
);
PREPARE farm_index_stmt FROM @farm_index_ddl;
EXECUTE farm_index_stmt;
DEALLOCATE PREPARE farm_index_stmt;

SET @farm_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_print_job'
      AND index_name = 'idx_print_job_active_status_created_id'
);
SET @farm_index_ddl = IF(
    @farm_index_exists = 0,
    'ALTER TABLE farm_print_job ADD INDEX idx_print_job_active_status_created_id (status, created_at, id)',
    'SELECT 1'
);
PREPARE farm_index_stmt FROM @farm_index_ddl;
EXECUTE farm_index_stmt;
DEALLOCATE PREPARE farm_index_stmt;
