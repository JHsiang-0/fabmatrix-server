-- SYNC-002.1 MySQL 8.4 只读预检。
-- 仅输出 issueType,id,name,gridRow,gridCol；本文件禁止任何写入或 DDL。
-- 迁移前请先在目标数据库上执行本文件，结果必须为空。

SELECT issueType, id, name, gridRow, gridCol
FROM (
    SELECT 'PARTIAL_COORDINATE' AS issueType,
           p.id,
           p.name,
           p.grid_row AS gridRow,
           p.grid_col AS gridCol
    FROM farm_printer p
    WHERE (p.grid_row IS NULL) <> (p.grid_col IS NULL)

    UNION ALL

    SELECT 'DUPLICATE_SLOT' AS issueType,
           p.id,
           p.name,
           p.grid_row AS gridRow,
           p.grid_col AS gridCol
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

    SELECT 'NON_POSITIVE_COORDINATE' AS issueType,
           p.id,
           p.name,
           p.grid_row AS gridRow,
           p.grid_col AS gridCol
    FROM farm_printer p
    WHERE p.grid_row IS NOT NULL
      AND p.grid_col IS NOT NULL
      AND (p.grid_row <= 0 OR p.grid_col <= 0)

    UNION ALL

    SELECT 'DEFAULT_LAYOUT_OUT_OF_BOUNDS' AS issueType,
           p.id,
           p.name,
           p.grid_row AS gridRow,
           p.grid_col AS gridCol
    FROM farm_printer p
    WHERE p.grid_row IS NOT NULL
      AND p.grid_col IS NOT NULL
      AND (p.grid_row > 4 OR p.grid_col > 12)
) coordinate_issues
ORDER BY issueType, id;
