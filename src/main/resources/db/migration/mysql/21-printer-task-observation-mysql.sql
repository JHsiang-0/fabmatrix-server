-- 为已有 MySQL 数据库补齐设备任务来源字段。
-- 执行前请备份数据库；本脚本只新增列，不修改已有任务和历史记录。

DELIMITER $$

DROP PROCEDURE IF EXISTS farm_add_status_history_column_if_missing$$
CREATE PROCEDURE farm_add_status_history_column_if_missing(
    IN p_column_name VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    DECLARE v_column_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer_status_history'
      AND column_name = p_column_name;

    IF v_column_count = 0 THEN
        SET @farm_history_ddl = CONCAT(
            'ALTER TABLE `farm_printer_status_history` ADD COLUMN `',
            p_column_name, '` ', p_definition
        );
        PREPARE farm_history_stmt FROM @farm_history_ddl;
        EXECUTE farm_history_stmt;
        DEALLOCATE PREPARE farm_history_stmt;
    END IF;
END$$

CALL farm_add_status_history_column_if_missing(
    'task_source',
    'VARCHAR(16) NOT NULL DEFAULT ''UNKNOWN'' COMMENT ''设备任务来源：FARM/EXTERNAL/UNKNOWN/NONE'' AFTER filename'
)$$
CALL farm_add_status_history_column_if_missing(
    'farm_job_id',
    'BIGINT NULL COMMENT ''观测时绑定的 Farm 平台任务 ID'' AFTER task_source'
)$$
CALL farm_add_status_history_column_if_missing(
    'task_match_reason',
    'VARCHAR(64) NULL COMMENT ''设备任务来源判定原因'' AFTER farm_job_id'
)$$

DROP PROCEDURE IF EXISTS farm_add_status_history_column_if_missing$$

DELIMITER ;
