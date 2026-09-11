-- SYNC-002.1 MySQL 8.4 增量迁移。
--
-- This script deliberately does not SIGNAL from the migration procedure. A
-- mysql batch client can stop on SIGNAL before reaching a trailing DROP
-- PROCEDURE, leaving a numbered migration routine behind. The procedure
-- records a failure and returns; the caller drops it on both paths and then
-- executes a deliberate failing statement only after cleanup.
--
-- The only layout DDL and default-row write in the MySQL initialization chain
-- is below, after the read-only coordinate precheck has completed.

DELIMITER $$

DROP PROCEDURE IF EXISTS farm_dashboard_layout_migrate$$
CREATE PROCEDURE farm_dashboard_layout_migrate()
migration: BEGIN
    DECLARE v_issue_count INT DEFAULT 0;
    DECLARE v_layout_object_count INT DEFAULT 0;
    DECLARE v_layout_base_table_count INT DEFAULT 0;
    DECLARE v_layout_column_count INT DEFAULT 0;
    DECLARE v_layout_expected_column_count INT DEFAULT 0;
    DECLARE v_layout_check_count INT DEFAULT 0;
    DECLARE v_layout_check_error_count INT DEFAULT 0;
    DECLARE v_layout_primary_key_count INT DEFAULT 0;
    DECLARE v_layout_primary_key_columns VARCHAR(255) DEFAULT '';
    DECLARE v_layout_time_error_count INT DEFAULT 0;
    DECLARE v_printer_column_count INT DEFAULT 0;
    DECLARE v_printer_pair_count INT DEFAULT 0;
    DECLARE v_printer_pair_error_count INT DEFAULT 0;
    DECLARE v_printer_positive_count INT DEFAULT 0;
    DECLARE v_printer_positive_error_count INT DEFAULT 0;
    DECLARE v_printer_index_object_count INT DEFAULT 0;
    DECLARE v_printer_index_row_count INT DEFAULT 0;
    DECLARE v_printer_index_non_unique INT DEFAULT 1;
    DECLARE v_printer_index_columns VARCHAR(255) DEFAULT '';

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET @farm_dashboard_layout_migration_failed = 1;
        SET @farm_dashboard_layout_migration_error =
            'SYNC-002.1 blocked: migration SQL failed before completion';
    END;

    SET @farm_dashboard_layout_migration_failed = 0;
    SET @farm_dashboard_layout_migration_error = NULL;

    -- Read-only precheck. Keep this result shape identical to the standalone
    -- precheck so an operator can inspect every affected printer.
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
        SET @farm_dashboard_layout_migration_failed = 1;
        SET @farm_dashboard_layout_migration_error =
            'SYNC-002.1 blocked: farm_printer coordinate precheck found issues';
        LEAVE migration;
    END IF;

    -- A same-named view or other object must not be mistaken for the layout
    -- table. Existing layout definitions are checked before any layout write.
    SELECT COUNT(*) INTO v_layout_object_count
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dashboard_layout';
    SELECT COUNT(*) INTO v_layout_base_table_count
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dashboard_layout'
      AND table_type = 'BASE TABLE';

    IF v_layout_object_count > 0 AND v_layout_base_table_count <> 1 THEN
        SET @farm_dashboard_layout_migration_failed = 1;
        SET @farm_dashboard_layout_migration_error =
            'SYNC-002.1 blocked: farm_dashboard_layout is not a base table';
        LEAVE migration;
    END IF;

    IF v_layout_base_table_count = 1 THEN
        SELECT COUNT(*),
               COALESCE(SUM(
                   (ordinal_position = 1 AND column_name = 'id'
                    AND column_type = 'bigint' AND is_nullable = 'NO'
                    AND extra = '')
                 OR (ordinal_position = 2 AND column_name = 'row_count'
                    AND column_type = 'smallint' AND is_nullable = 'NO'
                    AND extra = '')
                 OR (ordinal_position = 3 AND column_name = 'column_count'
                    AND column_type = 'smallint' AND is_nullable = 'NO'
                    AND extra = '')
                 OR (ordinal_position = 4 AND column_name = 'aisles_json'
                    AND column_type = 'text' AND is_nullable = 'NO'
                    AND extra = '')
                 OR (ordinal_position = 5 AND column_name = 'version'
                    AND column_type = 'bigint' AND is_nullable = 'NO'
                    AND extra = '')
                 OR (ordinal_position = 6 AND column_name = 'updated_by'
                    AND column_type = 'bigint' AND is_nullable = 'YES'
                    AND extra = '')
                 OR (ordinal_position = 7 AND column_name = 'created_at'
                    AND column_type = 'datetime' AND is_nullable = 'NO')
                 OR (ordinal_position = 8 AND column_name = 'updated_at'
                    AND column_type = 'datetime' AND is_nullable = 'NO')
               ), 0)
          INTO v_layout_column_count, v_layout_expected_column_count
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout';

        SELECT COUNT(*) INTO v_layout_check_count
        FROM information_schema.table_constraints
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout'
          AND constraint_type = 'CHECK';

        SELECT COUNT(*) INTO v_layout_check_error_count
        FROM (
            SELECT 'ck_farm_dashboard_layout_singleton' AS constraint_name,
                   'id=1' AS expected_clause
            UNION ALL SELECT 'ck_farm_dashboard_layout_rows', 'row_countbetween1and30'
            UNION ALL SELECT 'ck_farm_dashboard_layout_columns', 'column_countbetween1and60'
            UNION ALL SELECT 'ck_farm_dashboard_layout_cells', 'row_count*column_count<=1800'
            UNION ALL SELECT 'ck_farm_dashboard_layout_version', 'version>0'
        ) expected
        LEFT JOIN (
            SELECT tc.constraint_name, tc.enforced, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND cc.constraint_name = tc.constraint_name
            WHERE tc.table_schema = DATABASE()
              AND tc.table_name = 'farm_dashboard_layout'
              AND tc.constraint_type = 'CHECK'
        ) actual ON actual.constraint_name = expected.constraint_name
        WHERE actual.constraint_name IS NULL
           OR actual.enforced <> 'YES'
           OR REGEXP_REPLACE(LOWER(actual.check_clause), '[[:space:]`()]+', '')
              <> expected.expected_clause;

        SELECT COUNT(*) INTO v_layout_primary_key_count
        FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout'
          AND constraint_name = 'PRIMARY';
        SELECT COALESCE(GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ','), '')
          INTO v_layout_primary_key_columns
        FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout'
          AND constraint_name = 'PRIMARY';

        SELECT COUNT(*) INTO v_layout_time_error_count
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_dashboard_layout'
          AND (
              (column_name = 'created_at'
               AND (UPPER(REPLACE(COALESCE(column_default, ''), '()', ''))
                    <> 'CURRENT_TIMESTAMP'
                    OR LOWER(extra) LIKE '%on update%'))
              OR (column_name = 'updated_at'
                  AND (UPPER(REPLACE(COALESCE(column_default, ''), '()', ''))
                       <> 'CURRENT_TIMESTAMP'
                       OR LOWER(extra) NOT LIKE '%on update current_timestamp%'))
          );

        IF v_layout_column_count <> 8
           OR v_layout_expected_column_count <> 8
           OR v_layout_check_count <> 5
           OR v_layout_check_error_count <> 0
           OR v_layout_primary_key_count <> 1
           OR v_layout_primary_key_columns <> 'id'
           OR v_layout_time_error_count <> 0 THEN
            SET @farm_dashboard_layout_migration_failed = 1;
            SET @farm_dashboard_layout_migration_error =
                'SYNC-002.1 blocked: existing farm_dashboard_layout has incompatible structure';
            LEAVE migration;
        END IF;
    END IF;

    -- Existing farm_printer tables from before SYNC-002.1 may not have the
    -- database guards. Add only the missing guards, but block same-named wrong
    -- definitions instead of silently accepting them.
    SELECT COUNT(*) INTO v_printer_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND column_name IN ('grid_row', 'grid_col')
      AND data_type = 'int'
      AND is_nullable = 'YES';
    IF v_printer_column_count <> 2 THEN
        SET @farm_dashboard_layout_migration_failed = 1;
        SET @farm_dashboard_layout_migration_error =
            'SYNC-002.1 blocked: farm_printer coordinate columns have incompatible structure';
        LEAVE migration;
    END IF;

    SELECT COUNT(*) INTO v_printer_pair_count
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND constraint_name = 'ck_farm_printer_grid_pair'
      AND constraint_type = 'CHECK';
    IF v_printer_pair_count > 0 THEN
        SELECT COUNT(*) INTO v_printer_pair_error_count
        FROM information_schema.check_constraints cc
        JOIN information_schema.table_constraints tc
          ON tc.constraint_schema = cc.constraint_schema
         AND tc.constraint_name = cc.constraint_name
        WHERE tc.table_schema = DATABASE()
          AND tc.table_name = 'farm_printer'
          AND cc.constraint_name = 'ck_farm_printer_grid_pair'
          AND (tc.enforced <> 'YES'
               OR REGEXP_REPLACE(LOWER(cc.check_clause), '[[:space:]`()]+', '')
                  <> 'grid_rowisnullandgrid_colisnullorgrid_rowisnotnullandgrid_colisnotnull');
        IF v_printer_pair_count <> 1 OR v_printer_pair_error_count <> 0 THEN
            SET @farm_dashboard_layout_migration_failed = 1;
            SET @farm_dashboard_layout_migration_error =
                'SYNC-002.1 blocked: existing farm_printer pair check is incompatible';
            LEAVE migration;
        END IF;
    ELSE
        SET @farm_ddl = 'ALTER TABLE farm_printer ADD CONSTRAINT ck_farm_printer_grid_pair CHECK ((grid_row IS NULL AND grid_col IS NULL) OR (grid_row IS NOT NULL AND grid_col IS NOT NULL))';
        PREPARE farm_stmt FROM @farm_ddl;
        EXECUTE farm_stmt;
        DEALLOCATE PREPARE farm_stmt;
    END IF;

    SELECT COUNT(*) INTO v_printer_positive_count
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND constraint_name = 'ck_farm_printer_grid_positive'
      AND constraint_type = 'CHECK';
    IF v_printer_positive_count > 0 THEN
        SELECT COUNT(*) INTO v_printer_positive_error_count
        FROM information_schema.check_constraints cc
        JOIN information_schema.table_constraints tc
          ON tc.constraint_schema = cc.constraint_schema
         AND tc.constraint_name = cc.constraint_name
        WHERE tc.table_schema = DATABASE()
          AND tc.table_name = 'farm_printer'
          AND cc.constraint_name = 'ck_farm_printer_grid_positive'
          AND (tc.enforced <> 'YES'
               OR REGEXP_REPLACE(LOWER(cc.check_clause), '[[:space:]`()]+', '')
                  <> 'grid_rowisnullandgrid_colisnullorgrid_row>0andgrid_col>0');
        IF v_printer_positive_count <> 1 OR v_printer_positive_error_count <> 0 THEN
            SET @farm_dashboard_layout_migration_failed = 1;
            SET @farm_dashboard_layout_migration_error =
                'SYNC-002.1 blocked: existing farm_printer positive check is incompatible';
            LEAVE migration;
        END IF;
    ELSE
        SET @farm_ddl = 'ALTER TABLE farm_printer ADD CONSTRAINT ck_farm_printer_grid_positive CHECK ((grid_row IS NULL AND grid_col IS NULL) OR (grid_row > 0 AND grid_col > 0))';
        PREPARE farm_stmt FROM @farm_ddl;
        EXECUTE farm_stmt;
        DEALLOCATE PREPARE farm_stmt;
    END IF;

    SELECT COUNT(*) INTO v_printer_index_object_count
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND index_name = 'uk_farm_printer_grid_position';
    SELECT COUNT(*), COALESCE(MAX(non_unique), 1),
           COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','), '')
      INTO v_printer_index_row_count, v_printer_index_non_unique, v_printer_index_columns
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND index_name = 'uk_farm_printer_grid_position';
    IF v_printer_index_object_count > 0 THEN
        IF v_printer_index_row_count <> 2
           OR v_printer_index_non_unique <> 0
           OR v_printer_index_columns <> 'grid_row,grid_col' THEN
            SET @farm_dashboard_layout_migration_failed = 1;
            SET @farm_dashboard_layout_migration_error =
                'SYNC-002.1 blocked: existing farm_printer grid index is incompatible';
            LEAVE migration;
        END IF;
    ELSE
        SET @farm_ddl = 'ALTER TABLE farm_printer ADD UNIQUE INDEX uk_farm_printer_grid_position (grid_row, grid_col)';
        PREPARE farm_stmt FROM @farm_ddl;
        EXECUTE farm_stmt;
        DEALLOCATE PREPARE farm_stmt;
    END IF;

    IF v_layout_base_table_count = 0 THEN
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
-- This is intentionally after CALL so it also runs when the procedure's
-- handler caught a failure and returned control to the caller.
DROP PROCEDURE IF EXISTS farm_dashboard_layout_migrate$$

DELIMITER ;

-- mysql has no portable conditional SIGNAL outside a stored program. The
-- migration routine has already been removed, so a failed batch now stops on
-- this harmless, deterministic prepare/execute error without leaving a
-- routine behind. A successful migration executes SELECT 1 instead.
SET @farm_dashboard_layout_abort_sql = IF(
    @farm_dashboard_layout_migration_failed = 1,
    'SELECT * FROM farm_dashboard_layout_migration_abort',
    'SELECT 1'
);
SELECT @farm_dashboard_layout_migration_error AS migration_error
WHERE @farm_dashboard_layout_migration_failed = 1;
PREPARE farm_dashboard_layout_abort_stmt FROM @farm_dashboard_layout_abort_sql;
EXECUTE farm_dashboard_layout_abort_stmt;
DEALLOCATE PREPARE farm_dashboard_layout_abort_stmt;
SET @farm_dashboard_layout_migration_failed = NULL;
SET @farm_dashboard_layout_migration_error = NULL;
