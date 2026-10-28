-- ============================================
-- 修复打印任务表字段缺失问题
-- 日期: 2026-03-10
-- 说明: 添加实体类中有但数据库中缺失的字段
-- ============================================

-- 1. 添加 user_id 字段（对应实体类中的 userId）
# ALTER TABLE farm_print_job
#     ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT '发起任务的用户 ID' AFTER printer_id;

-- 2. 添加 error_reason 字段（对应实体类中的 errorReason）
ALTER TABLE farm_print_job
    ADD COLUMN error_reason VARCHAR(500) DEFAULT NULL COMMENT '失败原因（炒面、断料等）' AFTER completed_at;

-- 3. 添加 file_url 字段（对应实体类中的 fileUrl）
ALTER TABLE farm_print_job
    ADD COLUMN file_url VARCHAR(500) DEFAULT NULL COMMENT 'RustFS 中的文件访问地址' AFTER error_reason;

-- 4. 添加 est_time 字段（对应实体类中的 estTime）
ALTER TABLE farm_print_job
    ADD COLUMN est_time INT DEFAULT NULL COMMENT '预计打印耗时（秒）' AFTER file_url;

-- 5. 将 created_by 的数据迁移到 user_id（如果存在数据）
-- UPDATE farm_print_job SET user_id = created_by WHERE created_by IS NOT NULL;

-- 6. 可选：删除 created_by 字段（如果确认不再需要）
-- ALTER TABLE farm_print_job DROP COLUMN created_by;
