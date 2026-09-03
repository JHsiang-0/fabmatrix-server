-- v2 批量计划资源版本快照。
-- 执行前请备份已有数据库；回滚可执行：ALTER TABLE farm_dispatch_plan_item DROP COLUMN resource_fingerprint;
ALTER TABLE farm_dispatch_plan_item
    ADD COLUMN IF NOT EXISTS resource_fingerprint VARCHAR(64) NULL COMMENT '预览时文件/打印机资源摘要';
