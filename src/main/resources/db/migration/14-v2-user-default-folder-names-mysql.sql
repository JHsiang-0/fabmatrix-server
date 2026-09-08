-- 一次性手工迁移：将每个用户唯一的默认目录改为“<username>的文件”。
-- 执行前请备份数据库；本脚本只更新 is_default_folder=1 的目录，不修改普通目录。
-- 已有数据卷不会自动执行本脚本，请在 MySQL 中手动执行。

-- 仅报告存在多个默认目录的用户；这些用户不会被下面的 UPDATE 修改。
SELECT user_id, COUNT(*) AS default_folder_count
FROM farm_print_file
WHERE is_folder = 1
  AND is_default_folder = 1
  AND user_id IS NOT NULL
GROUP BY user_id
HAVING COUNT(*) > 1;

UPDATE farm_print_file f
JOIN farm_user u ON u.id = f.user_id
JOIN (
    SELECT user_id
    FROM farm_print_file
    WHERE is_folder = 1
      AND is_default_folder = 1
      AND user_id IS NOT NULL
    GROUP BY user_id
    HAVING COUNT(*) = 1
) unique_default ON unique_default.user_id = f.user_id
SET f.original_name = CONCAT(u.username, '的文件')
WHERE f.is_folder = 1
  AND f.is_default_folder = 1
  AND f.user_id IS NOT NULL;
