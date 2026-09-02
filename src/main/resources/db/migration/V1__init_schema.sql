-- ============================================
-- 3D 打印农场管理系统 - 数据库初始化脚本
-- 版本: V1.0.0
-- 说明: 完整的建表语句，适用于全新部署
-- ============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------------------------------
-- 1. 用户表 (farm_user)
-- ----------------------------------------------------
DROP TABLE IF EXISTS `farm_user`;
CREATE TABLE `farm_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码（加密存储）',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `role` VARCHAR(20) DEFAULT 'OPERATOR' COMMENT '角色：ADMIN/OPERATOR',
    `status` TINYINT DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ----------------------------------------------------
-- 2. 打印机设备表 (farm_printer)
-- ----------------------------------------------------
DROP TABLE IF EXISTS `farm_printer`;
CREATE TABLE `farm_printer` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(100) NOT NULL COMMENT '打印机名称（如：Voron-2.4-01）',
    `ip_address` VARCHAR(50) NOT NULL COMMENT '局域网 IP 地址',
    `mac_address` VARCHAR(50) DEFAULT NULL COMMENT 'MAC 地址（用于网络唤醒等）',
    `firmware_type` VARCHAR(50) DEFAULT 'Klipper' COMMENT '固件类型（Klipper, OctoPrint 等）',
    `api_key` VARCHAR(255) DEFAULT NULL COMMENT '上位机 API 通信密钥',
    `status` VARCHAR(20) DEFAULT 'OFFLINE' COMMENT '业务状态：IDLE, PRINTING, OFFLINE, ERROR, MAINTENANCE',
    `current_job_id` BIGINT DEFAULT NULL COMMENT '当前正在执行的打印任务 ID',
    `current_material` VARCHAR(50) DEFAULT 'ABS' COMMENT '当前装载的耗材类型 (如 PLA, PETG, ABS)',
    `nozzle_size` DECIMAL(10,2) DEFAULT 0.40 COMMENT '当前安装的喷嘴直径 (如 0.40, 0.60)',
    `machine_number` VARCHAR(50) DEFAULT NULL COMMENT '设备编号/机台号（用于产线管理）',
    `grid_row` TINYINT DEFAULT NULL COMMENT '物理位置 - 网格行号（数字孪生看板用，1-4，null 表示待分配区）',
    `grid_col` TINYINT DEFAULT NULL COMMENT '物理位置 - 网格列号（数字孪生看板用，1-12，null 表示待分配区）',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mac_address` (`mac_address`),
    KEY `idx_ip_address` (`ip_address`),
    KEY `idx_status` (`status`),
    KEY `idx_grid_position` (`grid_row`, `grid_col`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备资产与状态表';

-- ----------------------------------------------------
-- 3. 打印文件表 (farm_print_file)
-- ----------------------------------------------------
DROP TABLE IF EXISTS `farm_print_file`;
CREATE TABLE `farm_print_file` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `filename` VARCHAR(255) NOT NULL COMMENT '文件名',
    `original_name` VARCHAR(255) NOT NULL COMMENT '原始上传文件名',
    `file_path` VARCHAR(500) NOT NULL COMMENT '文件存储路径（相对路径或对象存储 key）',
    `file_size` BIGINT NOT NULL COMMENT '文件大小（字节）',
    `material_type` VARCHAR(50) DEFAULT NULL COMMENT '建议耗材类型',
    `estimated_time` INT DEFAULT NULL COMMENT '预计打印时间（秒）',
    `layer_height` DECIMAL(10,2) DEFAULT NULL COMMENT '层高（mm）',
    `nozzle_temp` INT DEFAULT NULL COMMENT '喷头温度（℃）',
    `bed_temp` INT DEFAULT NULL COMMENT '热床温度（℃）',
    `uploaded_by` BIGINT DEFAULT NULL COMMENT '上传用户ID',
    `status` VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DELETED',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_filename` (`filename`),
    KEY `idx_uploaded_by` (`uploaded_by`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='打印文件表';

-- ----------------------------------------------------
-- 4. 打印任务表 (farm_print_job)
-- ----------------------------------------------------
DROP TABLE IF EXISTS `farm_print_job`;
CREATE TABLE `farm_print_job` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `file_id` BIGINT NOT NULL COMMENT '关联的文件ID',
    `printer_id` BIGINT DEFAULT NULL COMMENT '分配的打印机ID（null表示未分配）',
    `material_type` VARCHAR(50) DEFAULT NULL COMMENT '实际使用的耗材类型',
    `nozzle_size` DECIMAL(10,2) DEFAULT NULL COMMENT '实际使用的喷嘴直径',
    `priority` INT DEFAULT 5 COMMENT '优先级（1-10，数字越小优先级越高）',
    `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '任务状态：PENDING/QUEUED/PRINTING/COMPLETED/CANCELLED/FAILED',
    `progress` DECIMAL(5,2) DEFAULT 0.00 COMMENT '打印进度（0-100）',
    `started_at` DATETIME DEFAULT NULL COMMENT '开始打印时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
    `created_by` BIGINT DEFAULT NULL COMMENT '创建用户ID',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_printer_id` (`printer_id`),
    KEY `idx_status` (`status`),
    KEY `idx_priority` (`priority`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='打印任务表';

-- ----------------------------------------------------
-- 5. 插入默认管理员账号
-- 密码: Admin123 (BCrypt加密后的值)
-- ----------------------------------------------------
INSERT INTO `farm_user` (`username`, `password_hash`, `email`, `phone`, `role`, `status`, `created_at`, `updated_at`) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EO', 'admin@example.com', '13800138000', 'ADMIN', 1, NOW(), NOW());

SET FOREIGN_KEY_CHECKS = 1;
