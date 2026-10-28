-- =============================================
-- 任务：支持 RustFS 虚拟文件夹分类 + 现场安全确认打印
-- 日期：2026-03-13
-- =============================================

-- 1. 为 farm_print_file 增加虚拟目录字段
ALTER TABLE `farm_print_file`
ADD COLUMN `parent_id` bigint NULL DEFAULT NULL COMMENT '父目录ID（NULL表示根目录）' AFTER `id`,
ADD COLUMN `is_folder` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否为文件夹（0-文件，1-文件夹）' AFTER `parent_id`,
ADD COLUMN `rustfs_key` varchar(500) NULL DEFAULT NULL COMMENT '对象存储真实路径（如 farm/uploads/xxx.gcode）' AFTER `is_folder`,
ADD INDEX `idx_parent_id`(`parent_id`),
ADD INDEX `idx_is_folder`(`is_folder`);

-- 2. 为 farm_print_job 增加 operator_id，删除冗余字段
ALTER TABLE `farm_print_job`
ADD COLUMN `operator_id` bigint NULL DEFAULT NULL COMMENT '现场操作员ID（确认安全、启动打印时记录）' AFTER `user_id`,
DROP COLUMN `file_url`,
DROP COLUMN `est_time`,
DROP COLUMN `material_type`,
DROP COLUMN `nozzle_size`;

-- 3. 为 farm_printer 增加 is_safe_to_print
ALTER TABLE `farm_printer`
ADD COLUMN `is_safe_to_print` tinyint(1) NOT NULL DEFAULT 0 COMMENT '热床是否已确认安全（0-未确认，1-已确认）' AFTER `status`;