-- Current application schema additions for a fresh Docker MySQL volume.
-- This script is intentionally additive; it preserves the legacy columns
-- present in farm.sql and avoids destructive refactoring during initialization.

ALTER TABLE farm_print_job
    ADD COLUMN operator_id BIGINT NULL DEFAULT NULL
        COMMENT '现场操作员ID（确认安全、启动打印时记录）' AFTER user_id;

ALTER TABLE farm_print_file
    ADD COLUMN parent_id BIGINT NULL DEFAULT NULL
        COMMENT '父目录ID（NULL表示根目录）' AFTER id,
    ADD COLUMN is_folder TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否为文件夹（0-文件，1-文件夹）' AFTER parent_id,
    ADD COLUMN rustfs_key VARCHAR(500) NULL DEFAULT NULL
        COMMENT '对象存储真实路径（如 farm/uploads/xxx.gcode）' AFTER is_folder,
    ADD INDEX idx_parent_id (parent_id),
    ADD INDEX idx_is_folder (is_folder);

ALTER TABLE farm_printer
    ADD COLUMN is_safe_to_print TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '热床是否已确认安全（0-未确认，1-已确认）' AFTER status;
