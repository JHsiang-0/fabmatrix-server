-- UI-004.20 SQLite 手工迁移。
-- 调用顺序（SQLite 原生不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS）：
-- 1. Local Edition 启动时由可重复的 LocalSchemaMigration 补齐 visibility 列；
-- 2. 对已有数据库手工确认 visibility 列已存在后，再执行本脚本。
-- 本脚本故意不包含 ADD COLUMN，因此在上述前提下可连续执行；列补齐和数据迁移职责分离。
-- 不得把 is_default_folder=false 当作 PUBLIC。

UPDATE farm_print_file SET visibility = 'PRIVATE'
 WHERE visibility IS NULL OR UPPER(visibility) NOT IN ('PRIVATE', 'PUBLIC');
CREATE INDEX IF NOT EXISTS idx_print_file_visibility_parent
    ON farm_print_file(visibility, parent_id, user_id);

-- 默认目录及其全部后代（包括历史上误标为 PUBLIC 的记录）必须是 PRIVATE。
WITH RECURSIVE default_tree(id, path, depth) AS (
    SELECT id, ',' || id || ',', 1
    FROM farm_print_file
    WHERE is_folder = 1 AND is_default_folder = 1
    UNION ALL
    SELECT child.id, default_tree.path || child.id || ',', default_tree.depth + 1
    FROM farm_print_file child
    JOIN default_tree ON child.parent_id = default_tree.id
    WHERE default_tree.depth < 1000
      AND instr(default_tree.path, ',' || child.id || ',') = 0
)
UPDATE farm_print_file
SET visibility = 'PRIVATE'
WHERE id IN (SELECT id FROM default_tree);

-- 诊断 1：每个 PUBLIC 节点及其所有后代，供发布审批核对。
WITH RECURSIVE public_tree(candidate_id, descendant_id, path, depth) AS (
    SELECT id, id, ',' || id || ',', 0
    FROM farm_print_file
    WHERE UPPER(visibility) = 'PUBLIC'
    UNION ALL
    SELECT tree.candidate_id, child.id,
           tree.path || child.id || ',', tree.depth + 1
    FROM public_tree tree
    JOIN farm_print_file child ON child.parent_id = tree.descendant_id
    WHERE tree.depth < 1000
      AND instr(tree.path, ',' || child.id || ',') = 0
)
SELECT candidate_id, descendant_id, depth
FROM public_tree
ORDER BY candidate_id, depth, descendant_id;

-- 诊断 2：孤儿节点。
SELECT child.id, child.parent_id, child.original_name
FROM farm_print_file child
LEFT JOIN farm_print_file parent ON parent.id = child.parent_id
WHERE child.parent_id IS NOT NULL AND parent.id IS NULL
ORDER BY child.id;

-- 诊断 3：父级记录不是文件夹。运行时会拒绝这类路径，迁移保留现场供人工处理。
SELECT child.id, child.parent_id, child.original_name, parent.is_folder
FROM farm_print_file child
JOIN farm_print_file parent ON parent.id = child.parent_id
WHERE parent.is_folder = 0
ORDER BY child.id;

-- 诊断 4：循环引用（按起点和首次重复节点报告）。递归上限避免异常数据耗尽会话资源。
WITH RECURSIVE ancestor_walk(start_id, current_id, path, has_cycle, depth) AS (
    SELECT id, id, ',' || id || ',', 0, 0
    FROM farm_print_file
    UNION ALL
    SELECT walk.start_id, parent.id,
           walk.path || parent.id || ',',
           CASE WHEN instr(walk.path, ',' || parent.id || ',') > 0 THEN 1 ELSE 0 END,
           walk.depth + 1
    FROM ancestor_walk walk
    JOIN farm_print_file current_node ON current_node.id = walk.current_id
    JOIN farm_print_file parent ON parent.id = current_node.parent_id
    WHERE walk.has_cycle = 0 AND walk.depth < 1000
)
SELECT start_id, current_id AS repeated_id
FROM ancestor_walk
WHERE has_cycle = 1
ORDER BY start_id, repeated_id;

-- 诊断 5：超过两级的目录。
WITH RECURSIVE folder_tree(id, path, folder_level) AS (
    SELECT id, ',' || id || ',', 1
    FROM farm_print_file
    WHERE is_folder = 1 AND parent_id IS NULL
    UNION ALL
    SELECT child.id, tree.path || child.id || ',',
           tree.folder_level + CASE WHEN child.is_folder = 1 THEN 1 ELSE 0 END
    FROM folder_tree tree
    JOIN farm_print_file child ON child.parent_id = tree.id
    WHERE tree.folder_level < 1000
      AND instr(tree.path, ',' || child.id || ',') = 0
)
SELECT id, folder_level
FROM folder_tree
WHERE folder_level > 2
ORDER BY folder_level DESC, id;

-- 诊断 6：按真实命名空间判断的同级目录重名。文件不参与；PRIVATE 根按用户隔离，PUBLIC 根共享。
SELECT
       CASE WHEN parent_id IS NULL THEN 'ROOT' ELSE 'CHILD' END AS namespace_kind,
       parent_id,
       CASE
           WHEN parent_id IS NULL AND UPPER(visibility) = 'PRIVATE' THEN user_id
           ELSE NULL
       END AS root_user_id,
       CASE
           WHEN parent_id IS NULL THEN UPPER(visibility)
           ELSE NULL
       END AS root_visibility,
       original_name, COUNT(*) AS duplicate_count,
       GROUP_CONCAT(id) AS ids
FROM farm_print_file
WHERE is_folder = 1
GROUP BY namespace_kind, parent_id, root_user_id, root_visibility, original_name
HAVING COUNT(*) > 1
ORDER BY namespace_kind, parent_id, root_user_id, root_visibility, original_name;

-- 诊断 7：父子 visibility 不一致。
SELECT child.id, child.parent_id, parent.id AS parent_record_id,
       parent.visibility AS parent_visibility, child.visibility AS child_visibility
FROM farm_print_file child
JOIN farm_print_file parent ON parent.id = child.parent_id
WHERE UPPER(parent.visibility) <> UPPER(child.visibility)
ORDER BY child.id;
