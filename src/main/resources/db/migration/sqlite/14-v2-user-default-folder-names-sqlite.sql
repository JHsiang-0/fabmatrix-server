-- 一次性手工迁移：将每个用户唯一的默认目录改为“<username>的文件”。
-- 执行前请备份数据库；本脚本只更新 is_default_folder=1 的目录，不修改普通目录。
-- 已有 SQLite 数据库不会自动执行本脚本，请在 SQLite 中手动执行。

-- 仅报告存在多个默认目录的用户；这些用户不会被下面的 UPDATE 修改。
SELECT user_id, COUNT(*) AS default_folder_count
FROM farm_print_file
WHERE is_folder = 1
  AND is_default_folder = 1
  AND user_id IS NOT NULL
GROUP BY user_id
HAVING COUNT(*) > 1;

UPDATE farm_print_file
SET original_name = (
    SELECT u.username || '的文件'
    FROM farm_user u
    WHERE u.id = farm_print_file.user_id
)
WHERE is_folder = 1
  AND is_default_folder = 1
  AND user_id IS NOT NULL
  AND (
      SELECT COUNT(*)
      FROM farm_print_file duplicate_check
      WHERE duplicate_check.user_id = farm_print_file.user_id
        AND duplicate_check.is_folder = 1
        AND duplicate_check.is_default_folder = 1
  ) = 1;
