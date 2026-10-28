-- ============================================
-- 添加打印文件表温度和层高字段
-- 日期: 2026-03-12
-- 说明: 支持 OrcaSlicer 切片参数解析
-- ============================================

ALTER TABLE farm_print_file ADD COLUMN bed_temp INT DEFAULT NULL COMMENT '热床温度（℃）';
ALTER TABLE farm_print_file ADD COLUMN nozzle_temp INT DEFAULT NULL COMMENT '喷头温度（℃）';
ALTER TABLE farm_print_file ADD COLUMN layer_height DECIMAL(10,2) DEFAULT NULL COMMENT '层高（mm）';
ALTER TABLE farm_print_file ADD COLUMN first_layer_nozzle_temp INT DEFAULT NULL COMMENT '首层喷头温度（℃）';
ALTER TABLE farm_print_file ADD COLUMN first_layer_bed_temp INT DEFAULT NULL COMMENT '首层热床温度（℃）';
ALTER TABLE farm_print_file ADD COLUMN first_layer_height DECIMAL(10,2) DEFAULT NULL COMMENT '首层层高（mm）';