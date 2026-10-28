-- ============================================
-- 扩展打印文件表字段
-- 日期: 2026-03-11
-- 说明: 添加缩略图、耗材预估重量和长度字段
-- ============================================

-- 1. 添加 thumbnail_url 字段（缩略图在 RustFS 中的地址）
ALTER TABLE farm_print_file
    ADD COLUMN thumbnail_url VARCHAR(500) DEFAULT NULL COMMENT '缩略图URL（G-code中提取的缩略图在RustFS中的地址）' AFTER nozzle_size;

-- 2. 添加 filament_weight 字段（耗材预估重量，单位：克）
ALTER TABLE farm_print_file
    ADD COLUMN filament_weight DECIMAL(10,2) DEFAULT NULL COMMENT '耗材预估重量（克）' AFTER thumbnail_url;

-- 3. 添加 filament_length 字段（耗材预估长度，单位：米）
ALTER TABLE farm_print_file
    ADD COLUMN filament_length DECIMAL(10,2) DEFAULT NULL COMMENT '耗材预估长度（米）' AFTER filament_weight;

-- 可选：为 user_id 和 created_at 添加索引以提升分页查询性能
CREATE INDEX idx_print_file_user_id ON farm_print_file(user_id);
CREATE INDEX idx_print_file_created_at ON farm_print_file(created_at);