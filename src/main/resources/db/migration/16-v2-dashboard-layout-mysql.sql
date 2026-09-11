-- SYNC-002.1 MySQL 8.4 增量迁移。
-- 执行前请先运行 dashboard-layout-precheck-mysql.sql。此脚本会在任何布局表
-- DDL 前再次输出并阻断全部坐标异常，不删除、不改写 farm_printer 坐标。
-- 已有同名但结构不符的 farm_dashboard_layout 会明确失败。

DELIMITER $$

DROP PROCEDURE IF EXISTS farm_dashboard_layout_migrate$$
CREATE PROCEDURE farm_dashboard_layout_migrate()
BEGIN
    DECLARE v_issue_count INT DEFAULT 0;
    DECLARE v_layout_table_count INT DEFAULT 0;
    DECLARE v_layout_column_count INT DEFAULT 0;
    DECLARE v_layout_expected_column_count INT DEFAULT 0;
    DECLARE v_layout_check_count INT DEFAULT 0;
    DECLARE v_layout_primary_key_count INT DEFAULT 0;

    -- 迁移内部预检：诊断结果与独立只读预检保持一致。
    SELECT issueType, id, name, gridRow, gridCol
    FROM (
        SELECT 'PARTIAL_COORDINATE' AS issueType, p.id, p.name,
               p.grid_row AS gridRow, p.grid_col AS gridCol
        FROM farm_printer p
        WHERE (p.grid_row IS NULL) <> (p.grid_col IS NULL)

        UNION ALL

        SELECT 'DUPLICATE_SLOT' AS issueType, p.id, p.name,
               p.grid_row AS gridRow, p.grid_col AS gridCol
        FROM farm_printer p
        JOIN (
            SELECT grid_row, grid_col
            FROM farm_printer
            WHERE grid_row IS NOT NULL AND grid_col IS NOT NULL
            GROUP BY grid_row, grid_col
            HAVING COUNT(*) > 1
        ) duplicate_slot
          ON duplicate_slot.grid_row = p.grid_row
         AND duplicate_slot.grid_col = p.grid_col

        UNION ALL

        SELECT 'NON_POSITIVE_COORDINATE' AS issueType, p.id, p.name,
               p.grid_row AS gridRow, p.grid_col AS gridCol
        FROM farm_printer p
        WHERE p.grid_row IS NOT NULL AND p.grid_col IS NOT NULL
          AND (p.grid_row <= 0 OR p.grid_col <= 0)

        UNION ALL

        SELECT 'DEFAULT_LAYOUT_OUT_OF_BOUNDS' AS issueType, p.id, p.name,
               p.grid_row AS gridRow, p.grid_col AS gridCol
        FROM farm_printer p
        WHERE p.grid_row IS NOT NULL AND p.grid_col IS NOT NULL
          AND (p.grid_row > 4 OR p.grid_col > 12)
    ) coordinate_issues
    ORDER BY issueType, id;

    SELECT COUNT(*) INTO v_issue_count
    FROM (
        SELECT p.id
        FROM farm_printer p
        WHERE (p.grid_row IS NULL) <> (p.grid_col IS NULL)
        UNION ALL
        SELECT p.id
        FROM farm_printer p
        JOIN (
            SELECT grid_row, grid_col
            FROM farm_printer
            WHERE grid_row IS NOT NULL AND grid_col IS NOT NULL
            GROUP BY grid_row, grid_col
            HAVING COUNT(*) > 1
        ) duplicate_slot
          ON duplicate_slot.grid_row = p.grid_row
         AND duplicate_slot.grid_col = p.grid_col
        UNION ALL
        SELECT p.id
        FROM farm_printer p
        WHERE p.grid_row IS NOT NULL AND p.grid_col IS NOT NULL
          AND (p.grid_row <= 0 OR p.grid_col <= 0)
        UNION ALL
        SELECT p.id
        FROM farm_printer p
        WHERE p.grid_row IS NOT NULL AND p.grid_col IS NOT NULL
          AND (p.grid_row > 4 OR p.grid_col > 12)
    ) coordinate_issue_rows;

    IF v_issue_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'SYNC-002.1 blocked: farm_printer coordinate precheck found issues';
    END IF;

    SELECT COUNT(*) INTO v_layout_table_count
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dashboard_layout';

    IF v_layout_table_count > 0 THEN
        SELECT COUNT(*) INTO v_layout_expected_column_count
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout'
          AND (
              (column_name = 'id' AND data_type = 'bigint' AND is_nullable = 'NO')
              OR (column_name = 'row_count' AND data_type = 'smallint' AND is_nullable = 'NO')
              OR (column_name = 'column_count' AND data_type = 'smallint' AND is_nullable = 'NO')
              OR (column_name = 'aisles_json' AND data_type = 'text' AND is_nullable = 'NO')
              OR (column_name = 'version' AND data_type = 'bigint' AND is_nullable = 'NO')
              OR (column_name = 'updated_by' AND data_type = 'bigint' AND is_nullable = 'YES')
              OR (column_name = 'created_at' AND data_type = 'datetime' AND is_nullable = 'NO')
              OR (column_name = 'updated_at' AND data_type = 'datetime' AND is_nullable = 'NO')
          );
        SELECT COUNT(*) INTO v_layout_column_count
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout';
        SELECT COUNT(*) INTO v_layout_check_count
        FROM information_schema.table_constraints
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout'
          AND constraint_type = 'CHECK'
          AND constraint_name IN (
              'ck_farm_dashboard_layout_singleton',
              'ck_farm_dashboard_layout_rows',
              'ck_farm_dashboard_layout_columns',
              'ck_farm_dashboard_layout_cells',
              'ck_farm_dashboard_layout_version'
          );
        SELECT COUNT(*) INTO v_layout_primary_key_count
        FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout'
          AND constraint_name = 'PRIMARY'
          AND column_name = 'id';

        IF v_layout_column_count <> 8
           OR v_layout_expected_column_count <> 8
           OR v_layout_check_count <> 5
           OR v_layout_primary_key_count <> 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'SYNC-002.1 blocked: existing farm_dashboard_layout has incompatible structure';
        END IF;
    ELSE
        CREATE TABLE farm_dashboard_layout (
            id BIGINT NOT NULL,
            row_count SMALLINT NOT NULL,
            column_count SMALLINT NOT NULL,
            aisles_json TEXT NOT NULL,
            version BIGINT NOT NULL,
            updated_by BIGINT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            CONSTRAINT ck_farm_dashboard_layout_singleton CHECK (id = 1),
            CONSTRAINT ck_farm_dashboard_layout_rows CHECK (row_count BETWEEN 1 AND 30),
            CONSTRAINT ck_farm_dashboard_layout_columns CHECK (column_count BETWEEN 1 AND 60),
            CONSTRAINT ck_farm_dashboard_layout_cells CHECK (row_count * column_count <= 1800),
            CONSTRAINT ck_farm_dashboard_layout_version CHECK (version > 0)
        ) ENGINE = InnoDB
          DEFAULT CHARACTER SET = utf8mb4
          COLLATE = utf8mb4_general_ci
          COMMENT = '农场级动态看板布局';
    END IF;

    INSERT INTO farm_dashboard_layout
        (id, row_count, column_count, aisles_json, version, updated_by)
    VALUES
        (1, 4, 12,
         '[{"id":"default-column-aisle","axis":"COLUMN","afterIndex":7,"label":"过道","size":100}]',
         1, NULL)
    ON DUPLICATE KEY UPDATE id = VALUES(id);
END$$

CALL farm_dashboard_layout_migrate()$$
DROP PROCEDURE IF EXISTS farm_dashboard_layout_migrate$$

DELIMITER ;
