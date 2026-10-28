-- ============================================
-- 修复打印文件表字段缺失问题
-- 日期: 2026-03-10
-- 说明: 添加实体类中有但数据库中缺失的字段
-- ============================================

-- 1. 添加 safe_name 字段（对应实体类中的 safeName）
ALTER TABLE farm_print_file
    ADD COLUMN safe_name VARCHAR(255) DEFAULT NULL COMMENT '安全文件名（带时间戳）' AFTER original_name;

-- 2. 添加 user_id 字段（对应实体类中的 userId）
ALTER TABLE farm_print_file
    ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT '上传用户ID' AFTER file_size;

-- 3. 添加 est_time 字段（对应实体类中的 estTime）
ALTER TABLE farm_print_file
    ADD COLUMN est_time INT DEFAULT NULL COMMENT '预计打印耗时（秒）' AFTER user_id;

-- 4. 添加 material_type 字段（对应实体类中的 materialType）
ALTER TABLE farm_print_file
    ADD COLUMN material_type VARCHAR(50) DEFAULT NULL COMMENT '耗材类型（如 PLA, PETG, ABS）' AFTER est_time;

-- 5. 添加 nozzle_size 字段（对应实体类中的 nozzleSize）
ALTER TABLE farm_print_file
    ADD COLUMN nozzle_size DECIMAL(10,2) DEFAULT NULL COMMENT '喷嘴直径（如 0.40, 0.60）' AFTER material_type;

-- 6. 将 uploaded_by 的数据迁移到 user_id（如果存在数据）
-- UPDATE farm_print_file SET user_id = uploaded_by WHERE uploaded_by IS NOT NULL;

-- 7. 可选：删除 uploaded_by 字段（如果确认不再需要）
# ALTER TABLE farm_print_file DROP COLUMN uploaded_by;
