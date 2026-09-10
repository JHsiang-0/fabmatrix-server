-- UI-004.20 SQLite 手工迁移。
-- 这是受控的一次性脚本：执行前备份 farm.db，执行后写入运维迁移记录；
-- Local Edition 正常启动会由 LocalSchemaMigration 以可重复方式完成同等操作。
-- 不得把 is_default_folder=false 当作 PUBLIC。

ALTER TABLE farm_print_file ADD COLUMN visibility VARCHAR(7) NOT NULL DEFAULT 'PRIVATE';
UPDATE farm_print_file SET visibility = 'PRIVATE'
 WHERE visibility IS NULL OR UPPER(visibility) NOT IN ('PRIVATE', 'PUBLIC');
CREATE INDEX IF NOT EXISTS idx_print_file_visibility_parent
    ON farm_print_file(visibility, parent_id, user_id);

-- 审计预览；输出后仅由管理员确认的目录集合执行受控 UPDATE。
SELECT f.id, f.user_id, f.parent_id, f.original_name,
       f.is_folder, f.is_default_folder, f.visibility,
       (SELECT COUNT(*) FROM farm_print_file child WHERE child.parent_id = f.id) AS direct_child_count
FROM farm_print_file f
WHERE f.is_default_folder = 0
ORDER BY f.user_id, f.parent_id, f.id;

-- 示例（不要直接执行，先替换为已审核的 ID 清单）：
-- UPDATE farm_print_file SET visibility = 'PUBLIC'
-- WHERE id IN (...) AND is_folder = 1 AND parent_id IS NULL;
-- UPDATE farm_print_file SET visibility = 'PUBLIC'
-- WHERE id IN (...) AND parent_id IN (...);
