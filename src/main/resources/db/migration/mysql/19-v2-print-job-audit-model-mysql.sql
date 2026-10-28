-- TC-007：历史快照与重试审计模型（MySQL 8）。
-- 本脚本不由 Flyway 执行；已有数据卷升级前必须备份并手工执行。
-- 所有列、回填和索引步骤均可重复执行；不建立 farm_print_job 自引用外键。
-- 迁移只能回填当前仍存在的文件、打印机和用户名称；operator 仅以 started_at 非空作为实际开始证据。
-- 历史改名及已删除/丢失字段不可恢复，READY 且 started_at 为空不会推断 operator 快照。

SET @farm_add_job_column = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND column_name = 'file_name_snapshot') = 0,
    'ALTER TABLE farm_print_job ADD COLUMN file_name_snapshot VARCHAR(255) NULL COMMENT ''创建时文件名快照''',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_job_column; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

SET @farm_add_job_column = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND column_name = 'printer_name_snapshot') = 0,
    'ALTER TABLE farm_print_job ADD COLUMN printer_name_snapshot VARCHAR(50) NULL COMMENT ''派发时打印机名称快照''',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_job_column; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

SET @farm_add_job_column = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND column_name = 'machine_number_snapshot') = 0,
    'ALTER TABLE farm_print_job ADD COLUMN machine_number_snapshot VARCHAR(50) NULL COMMENT ''派发时机器编号快照''',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_job_column; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

SET @farm_add_job_column = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND column_name = 'creator_username_snapshot') = 0,
    'ALTER TABLE farm_print_job ADD COLUMN creator_username_snapshot VARCHAR(50) NULL COMMENT ''创建时用户名快照''',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_job_column; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

SET @farm_add_job_column = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND column_name = 'operator_username_snapshot') = 0,
    'ALTER TABLE farm_print_job ADD COLUMN operator_username_snapshot VARCHAR(50) NULL COMMENT ''真正启动时操作员用户名快照''',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_job_column; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

SET @farm_add_job_column = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND column_name = 'retry_of_job_id') = 0,
    'ALTER TABLE farm_print_job ADD COLUMN retry_of_job_id BIGINT NULL COMMENT ''直接来源任务 ID''',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_job_column; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

SET @farm_add_job_column = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND column_name = 'root_job_id') = 0,
    'ALTER TABLE farm_print_job ADD COLUMN root_job_id BIGINT NULL COMMENT ''首次任务 ID''',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_job_column; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

SET @farm_add_job_column = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND column_name = 'attempt_number') = 0,
    'ALTER TABLE farm_print_job ADD COLUMN attempt_number INT NOT NULL DEFAULT 1 COMMENT ''任务尝试序号''',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_job_column; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

UPDATE farm_print_job
SET root_job_id = id
WHERE root_job_id IS NULL;

UPDATE farm_print_job
SET attempt_number = 1
WHERE attempt_number IS NULL OR attempt_number < 1;

UPDATE farm_print_job j
LEFT JOIN farm_print_file f ON f.id = j.file_id
LEFT JOIN farm_printer p ON p.id = j.printer_id
LEFT JOIN farm_user creator_user ON creator_user.id = j.user_id
LEFT JOIN farm_user operator_user ON operator_user.id = j.operator_id
SET j.file_name_snapshot = COALESCE(j.file_name_snapshot, f.original_name),
    j.printer_name_snapshot = COALESCE(j.printer_name_snapshot, p.name),
    j.machine_number_snapshot = COALESCE(j.machine_number_snapshot, p.machine_number),
    j.creator_username_snapshot = COALESCE(j.creator_username_snapshot, creator_user.username),
    j.operator_username_snapshot = CASE
        WHEN j.started_at IS NOT NULL
            THEN COALESCE(j.operator_username_snapshot, operator_user.username)
        ELSE j.operator_username_snapshot
    END
WHERE j.file_name_snapshot IS NULL
   OR j.printer_name_snapshot IS NULL
   OR j.machine_number_snapshot IS NULL
   OR j.creator_username_snapshot IS NULL
   OR (j.started_at IS NOT NULL AND j.operator_username_snapshot IS NULL);

SET @farm_add_retry_index = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND index_name = 'uk_print_job_retry_of_job') = 0,
    'ALTER TABLE farm_print_job ADD UNIQUE KEY uk_print_job_retry_of_job (retry_of_job_id)',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_retry_index; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;

SET @farm_add_root_index = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'farm_print_job'
       AND index_name = 'uk_print_job_root_attempt') = 0,
    'ALTER TABLE farm_print_job ADD UNIQUE KEY uk_print_job_root_attempt (root_job_id, attempt_number)',
    'SELECT 1');
PREPARE farm_stmt FROM @farm_add_root_index; EXECUTE farm_stmt; DEALLOCATE PREPARE farm_stmt;
