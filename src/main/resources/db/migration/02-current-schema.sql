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

DROP PROCEDURE IF EXISTS farm_add_check_if_missing$$
CREATE PROCEDURE farm_add_check_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_constraint_name VARCHAR(64),
    IN p_check_clause TEXT
)
BEGIN
    DECLARE v_constraint_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_constraint_count
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND constraint_name = p_constraint_name
      AND constraint_type = 'CHECK';

    IF v_constraint_count = 0 THEN
        SET @farm_ddl = CONCAT(
            'ALTER TABLE `', p_table_name, '` ADD CONSTRAINT `', p_constraint_name,
            '` CHECK ', p_check_clause
        );
        PREPARE farm_stmt FROM @farm_ddl;
        EXECUTE farm_stmt;
        DEALLOCATE PREPARE farm_stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS farm_add_unique_index_if_missing$$
CREATE PROCEDURE farm_add_unique_index_if_missing(
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
            'ALTER TABLE `', p_table_name, '` ADD UNIQUE INDEX `', p_index_name,
            '` (', p_columns, ')'
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
    'farm_print_file', 'is_default_folder',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否为用户系统默认目录（0-否，1-是）'' AFTER `is_folder`'
)$$
CALL farm_add_column_if_missing(
    'farm_print_file', 'visibility',
    'VARCHAR(7) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''显式可见性：PRIVATE 或 PUBLIC'' AFTER `is_default_folder`'
)$$
CALL farm_add_column_if_missing(
    'farm_print_file', 'rustfs_key',
    'VARCHAR(500) NULL DEFAULT NULL COMMENT ''对象存储真实路径（如 farm/uploads/xxx.gcode）'' AFTER `is_folder`'
)$$
CALL farm_add_index_if_missing('farm_print_file', 'idx_parent_id', '`parent_id`')$$
CALL farm_add_index_if_missing('farm_print_file', 'idx_is_folder', '`is_folder`')$$
CALL farm_add_index_if_missing('farm_print_file', 'idx_print_file_visibility_parent', '`visibility`, `parent_id`, `user_id`')$$

CALL farm_add_column_if_missing(
    'farm_printer', 'is_safe_to_print',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''热床是否已确认安全（0-未确认，1-已确认）'' AFTER `status`'
)$$

CALL farm_add_column_if_missing(
    'farm_printer', 'grid_row',
    'INT NULL DEFAULT NULL COMMENT ''物理网格所在行（由看板布局服务校验）'' AFTER `nozzle_size`'
)$$

CALL farm_add_column_if_missing(
    'farm_printer', 'grid_col',
    'INT NULL DEFAULT NULL COMMENT ''物理网格所在列（由看板布局服务校验）'' AFTER `grid_row`'
)$$

-- SYNC-002.1 printer coordinate integrity only. Layout DDL and its default row
-- belong exclusively to migration 16 after its read-only precheck succeeds.
CALL farm_add_check_if_missing(
    'farm_printer', 'ck_farm_printer_grid_pair',
    '((`grid_row` IS NULL AND `grid_col` IS NULL) OR (`grid_row` IS NOT NULL AND `grid_col` IS NOT NULL))'
)$$
CALL farm_add_check_if_missing(
    'farm_printer', 'ck_farm_printer_grid_positive',
    '((`grid_row` IS NULL AND `grid_col` IS NULL) OR (`grid_row` > 0 AND `grid_col` > 0))'
)$$
CALL farm_add_unique_index_if_missing(
    'farm_printer', 'uk_farm_printer_grid_position', '`grid_row`, `grid_col`'
)$$

DROP PROCEDURE IF EXISTS farm_add_column_if_missing$$
DROP PROCEDURE IF EXISTS farm_add_index_if_missing$$
DROP PROCEDURE IF EXISTS farm_add_check_if_missing$$
DROP PROCEDURE IF EXISTS farm_add_unique_index_if_missing$$

DELIMITER ;
