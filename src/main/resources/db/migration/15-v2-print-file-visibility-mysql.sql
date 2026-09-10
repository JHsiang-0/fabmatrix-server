-- UI-004.20 显式文件库可见性迁移（MySQL 8）。
-- 本脚本可重复执行：只补齐列/索引并将未知或 NULL 值安全收敛为 PRIVATE。
-- 绝不根据 is_default_folder=false 自动公开历史目录或文件。

DELIMITER $$

DROP PROCEDURE IF EXISTS farm_file_visibility_add_column_if_missing$$
CREATE PROCEDURE farm_file_visibility_add_column_if_missing()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_print_file'
          AND column_name = 'visibility'
    ) THEN
        ALTER TABLE farm_print_file
            ADD COLUMN visibility VARCHAR(7) NOT NULL DEFAULT 'PRIVATE'
                COMMENT '显式可见性：PRIVATE 或 PUBLIC' AFTER is_default_folder;
    END IF;
END$$

CALL farm_file_visibility_add_column_if_missing()$$

UPDATE farm_print_file
SET visibility = 'PRIVATE'
WHERE visibility IS NULL OR UPPER(visibility) NOT IN ('PRIVATE', 'PUBLIC')$$

DROP PROCEDURE IF EXISTS farm_file_visibility_add_index_if_missing$$
CREATE PROCEDURE farm_file_visibility_add_index_if_missing()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'farm_print_file'
          AND index_name = 'idx_print_file_visibility_parent'
    ) THEN
        ALTER TABLE farm_print_file
            ADD INDEX idx_print_file_visibility_parent (visibility, parent_id, user_id);
    END IF;
END$$

CALL farm_file_visibility_add_index_if_missing()$$

-- 审计预览：这些历史非默认节点仍为 PRIVATE，需管理员逐项确认后另行受控发布。
SELECT f.id, f.user_id, f.parent_id, f.original_name,
       f.is_folder, f.is_default_folder, f.visibility,
       (SELECT COUNT(*) FROM farm_print_file child WHERE child.parent_id = f.id) AS direct_child_count
FROM farm_print_file f
WHERE f.is_default_folder = 0
ORDER BY f.user_id, f.parent_id, f.id$$

DROP PROCEDURE IF EXISTS farm_file_visibility_add_column_if_missing$$
DROP PROCEDURE IF EXISTS farm_file_visibility_add_index_if_missing$$

DELIMITER ;
