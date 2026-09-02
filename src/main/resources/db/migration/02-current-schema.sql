-- Current application schema additions for a fresh Docker MySQL volume.
-- This script is additive and repeatable: it preserves legacy columns in
-- farm.sql and can also be safely rerun during an existing-volume upgrade.

DELIMITER $$

DROP PROCEDURE IF EXISTS farm_add_column_if_missing$$
CREATE PROCEDURE farm_add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    DECLARE v_column_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND column_name = p_column_name;

    IF v_column_count = 0 THEN
        SET @farm_ddl = CONCAT(
            'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_definition
        );
        PREPARE farm_stmt FROM @farm_ddl;
        EXECUTE farm_stmt;
        DEALLOCATE PREPARE farm_stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS farm_add_index_if_missing$$
CREATE PROCEDURE farm_add_index_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_columns TEXT
)
BEGIN
    DECLARE v_index_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_index_count
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND index_name = p_index_name;

    IF v_index_count = 0 THEN
        SET @farm_ddl = CONCAT(
            'ALTER TABLE `', p_table_name, '` ADD INDEX `', p_index_name, '` (', p_columns, ')'
        );
        PREPARE farm_stmt FROM @farm_ddl;
        EXECUTE farm_stmt;
        DEALLOCATE PREPARE farm_stmt;
    END IF;
END$$

CALL farm_add_column_if_missing(
    'farm_print_job', 'operator_id',
    'BIGINT NULL DEFAULT NULL COMMENT ''现场操作员ID（确认安全、启动打印时记录）'' AFTER `user_id`'
)$$

CALL farm_add_column_if_missing(
    'farm_print_file', 'parent_id',
    'BIGINT NULL DEFAULT NULL COMMENT ''父目录ID（NULL表示根目录）'' AFTER `id`'
)$$
CALL farm_add_column_if_missing(
    'farm_print_file', 'is_folder',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否为文件夹（0-文件，1-文件夹）'' AFTER `parent_id`'
)$$
CALL farm_add_column_if_missing(
    'farm_print_file', 'rustfs_key',
    'VARCHAR(500) NULL DEFAULT NULL COMMENT ''对象存储真实路径（如 farm/uploads/xxx.gcode）'' AFTER `is_folder`'
)$$
CALL farm_add_index_if_missing('farm_print_file', 'idx_parent_id', '`parent_id`')$$
CALL farm_add_index_if_missing('farm_print_file', 'idx_is_folder', '`is_folder`')$$

CALL farm_add_column_if_missing(
    'farm_printer', 'is_safe_to_print',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''热床是否已确认安全（0-未确认，1-已确认）'' AFTER `status`'
)$$

DROP PROCEDURE IF EXISTS farm_add_column_if_missing$$
DROP PROCEDURE IF EXISTS farm_add_index_if_missing$$

DELIMITER ;
