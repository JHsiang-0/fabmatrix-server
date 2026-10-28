-- UI-004.19 文件目录契约迁移。
-- 仅新增目录字段、默认目录和索引；不删除文件、不改写 print_job.file_id。
-- 对重复默认目录、同名目录等历史异常只报告并保留现场，人工处理后可再次执行。

DELIMITER $$

DROP PROCEDURE IF EXISTS farm_file_add_column_if_missing$$
CREATE PROCEDURE farm_file_add_column_if_missing(
    IN p_column_name VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    DECLARE v_column_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_print_file'
      AND column_name = p_column_name;

    IF v_column_count = 0 THEN
        SET @farm_file_ddl = CONCAT(
            'ALTER TABLE `farm_print_file` ADD COLUMN `', p_column_name, '` ', p_definition
        );
        PREPARE farm_file_stmt FROM @farm_file_ddl;
        EXECUTE farm_file_stmt;
        DEALLOCATE PREPARE farm_file_stmt;
    END IF;
END$$

CALL farm_file_add_column_if_missing(
    'parent_id',
    'BIGINT NULL DEFAULT NULL COMMENT ''父目录ID（NULL表示一级目录）'' AFTER `id`'
)$$
CALL farm_file_add_column_if_missing(
    'is_folder',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否为文件夹（0-文件，1-文件夹）'' AFTER `parent_id`'
)$$
CALL farm_file_add_column_if_missing(
    'is_default_folder',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否为用户系统默认目录（0-否，1-是）'' AFTER `is_folder`'
)$$

-- MySQL 没有直接的部分唯一索引。生成列只为默认目录生成 user_id，普通文件夹为 NULL，
-- 从而保证每个用户最多一个默认目录，同时不限制普通目录同名策略以外的历史数据。
CALL farm_file_add_column_if_missing(
    'default_folder_user_id',
    'BIGINT GENERATED ALWAYS AS (IF(`is_default_folder` = 1 AND `user_id` IS NOT NULL, `user_id`, NULL)) STORED AFTER `user_id`'
)$$

DROP PROCEDURE IF EXISTS farm_file_add_default_index_if_safe$$
CREATE PROCEDURE farm_file_add_default_index_if_safe()
BEGIN
    DECLARE v_duplicate_count INT DEFAULT 0;
    DECLARE v_index_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_duplicate_count
    FROM (
        SELECT user_id
        FROM farm_print_file
        WHERE is_folder = 1
          AND is_default_folder = 1
          AND user_id IS NOT NULL
        GROUP BY user_id
        HAVING COUNT(*) > 1
    ) duplicate_users;

    SELECT COUNT(*) INTO v_index_count
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_print_file'
      AND index_name = 'uk_print_file_default_folder_user';

    IF v_duplicate_count = 0 AND v_index_count = 0 THEN
        ALTER TABLE farm_print_file
            ADD UNIQUE INDEX uk_print_file_default_folder_user (default_folder_user_id);
    ELSEIF v_duplicate_count > 0 THEN
        SELECT 'UI-004.19: duplicate default folders detected; unique index was not added' AS migration_warning;
    END IF;
END$$

CALL farm_file_add_default_index_if_safe()$$

-- 同名根目录会违反新的同级命名契约，跳过该用户并输出诊断，不静默改名或覆盖。
SELECT f.user_id, f.id, f.original_name AS conflicting_folder_name
FROM farm_print_file f
WHERE f.is_folder = 1
  AND f.parent_id IS NULL
  AND f.original_name = '我的文件夹'
  AND f.is_default_folder = 0$$

INSERT INTO farm_print_file (parent_id, is_folder, is_default_folder, original_name, safe_name, user_id)
SELECT NULL, 1, 1, '我的文件夹', CONCAT('folder_default_', u.id), u.id
FROM farm_user u
LEFT JOIN farm_print_file d
       ON d.user_id = u.id
      AND d.is_folder = 1
      AND d.is_default_folder = 1
LEFT JOIN farm_print_file collision
       ON collision.user_id = u.id
      AND collision.parent_id IS NULL
      AND collision.is_folder = 1
      AND collision.is_default_folder = 0
      AND collision.original_name = '我的文件夹'
WHERE d.id IS NULL
  AND collision.id IS NULL$$

-- 仅迁移用户根目录下的文件；已有根目录文件夹保持一级同级节点。
-- 一个用户存在多个默认目录时不移动，避免选择错误的目标目录。
UPDATE farm_print_file f
JOIN (
    SELECT user_id, MIN(id) AS default_id
    FROM farm_print_file
    WHERE is_folder = 1
      AND is_default_folder = 1
      AND user_id IS NOT NULL
    GROUP BY user_id
    HAVING COUNT(*) = 1
) d ON d.user_id = f.user_id
SET f.parent_id = d.default_id
WHERE f.parent_id IS NULL
  AND f.is_folder = 0
  AND f.user_id IS NOT NULL
  AND UPPER(COALESCE(f.visibility, 'PRIVATE')) = 'PRIVATE'$$

DROP PROCEDURE IF EXISTS farm_file_add_default_index_if_safe$$
DROP PROCEDURE IF EXISTS farm_file_add_column_if_missing$$

DELIMITER ;
