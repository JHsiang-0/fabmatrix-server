-- v2 批量计划资源版本快照。
-- 执行前请备份已有数据库；回滚可执行：ALTER TABLE farm_dispatch_plan_item DROP COLUMN resource_fingerprint;

SET @farm_has_resource_fingerprint = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_dispatch_plan_item'
      AND column_name = 'resource_fingerprint'
);
SET @farm_add_resource_fingerprint = IF(
    @farm_has_resource_fingerprint = 0,
    'ALTER TABLE farm_dispatch_plan_item ADD COLUMN resource_fingerprint VARCHAR(64) NULL COMMENT ''预览时文件/打印机资源摘要''',
    'SELECT 1'
);
PREPARE farm_stmt FROM @farm_add_resource_fingerprint;
EXECUTE farm_stmt;
DEALLOCATE PREPARE farm_stmt;
