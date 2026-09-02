/*
 Navicat Premium Dump SQL

 Source Server         : WSL
 Source Server Type    : MySQL
 Source Server Version : 80408 (8.4.8)
 Source Host           : localhost:3306
 Source Schema         : farm

 Target Server Type    : MySQL
 Target Server Version : 80408 (8.4.8)
 File Encoding         : 65001

 Date: 13/03/2026 14:27:10
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for farm_print_file
-- ----------------------------
DROP TABLE IF EXISTS `farm_print_file`;
CREATE TABLE `farm_print_file`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `original_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原始文件名',
  `safe_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '安全文件名（带时间戳）',
  `file_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '文件存储URL',
  `file_size` bigint NULL DEFAULT NULL COMMENT '文件大小（字节）',
  `user_id` bigint NULL DEFAULT NULL COMMENT '上传用户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `est_time` int NULL DEFAULT NULL COMMENT '预计打印耗时（秒）',
  `material_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '耗材类型（如 PLA, PETG, ABS）',
  `nozzle_size` decimal(3, 2) NULL DEFAULT NULL COMMENT '喷嘴直径（如 0.40, 0.60）',
  `thumbnail_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '缩略图URL（G-code中提取的缩略图在RustFS中的地址）',
  `filament_weight` decimal(10, 2) NULL DEFAULT NULL COMMENT '耗材预估重量（克）',
  `filament_length` decimal(10, 2) NULL DEFAULT NULL COMMENT '耗材预估长度（米）',
  `bed_temp` int NULL DEFAULT NULL COMMENT '热床温度（℃）',
  `nozzle_temp` int NULL DEFAULT NULL COMMENT '喷头温度（℃）',
  `layer_height` decimal(10, 2) NULL DEFAULT NULL COMMENT '层高（mm）',
  `first_layer_nozzle_temp` int NULL DEFAULT NULL COMMENT '首层喷头温度（℃）',
  `first_layer_bed_temp` int NULL DEFAULT NULL COMMENT '首层热床温度（℃）',
  `first_layer_height` decimal(10, 2) NULL DEFAULT NULL COMMENT '首层层高（mm）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE,
  INDEX `idx_print_file_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_print_file_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 42 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '打印文件表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of farm_print_file
-- ----------------------------
INSERT INTO `farm_print_file` VALUES (41, '立方体_0.7mm_ABS_Generic Klipper Printer_56m0s.gcode', '1773367960017_立方体_0.7mm_ABS_Generic Klipper Printer_56m0s.gcode', 'http://127.0.0.1:9000/farm/1773367960017_立方体_0.7mm_ABS_Generic Klipper Printer_56m0s.gcode', 1238070, 1, '2026-03-13 10:12:40', 3360, 'ABS', 1.20, 'http://127.0.0.1:9000/farm/thumbnails/1773367960017_立方体_0.7mm_ABS_Generic Klipper Printer_56m0s.png', 321.08, 32.09, 30, 260, 0.70, 260, 60, 0.80);

-- ----------------------------
-- Table structure for farm_print_job
-- ----------------------------
DROP TABLE IF EXISTS `farm_print_job`;
CREATE TABLE `farm_print_job`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务流水号',
  `file_id` bigint NULL DEFAULT NULL COMMENT '关联的切片文件ID',
  `printer_id` bigint NULL DEFAULT NULL COMMENT '分配的打印机ID（排队中为NULL）',
  `user_id` bigint NOT NULL COMMENT '发起任务的用户ID',
  `priority` int NULL DEFAULT 0 COMMENT '排队优先级（数值越高越优先）',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态：QUEUED, ASSIGNED, READY, PRINTING, PAUSED, COMPLETED, FAILED, CANCELLED',
  `progress` decimal(5, 2) NULL DEFAULT 0.00 COMMENT '打印进度（0.00 - 100.00）',
  `started_at` datetime NULL DEFAULT NULL COMMENT '实际开始打印时间',
  `completed_at` datetime NULL DEFAULT NULL COMMENT '实际完成/失败时间',
  `error_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '失败原因（炒面、断料等）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `file_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'RustFS中的文件访问地址',
  `est_time` int NULL DEFAULT NULL COMMENT '预计打印耗时（秒）',
  `material_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '要求耗材类型(如 PLA, PETG, ABS)',
  `nozzle_size` decimal(3, 2) NULL DEFAULT NULL COMMENT '要求喷嘴直径(如 0.40, 0.60)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_printer_id`(`printer_id` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '打印任务与排队调度表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of farm_print_job
-- ----------------------------
INSERT INTO `farm_print_job` VALUES (1, 2, 289, 1, 100, 'ASSIGNED', 0.00, '2026-03-03 15:00:06', NULL, NULL, '2026-03-03 14:59:58', '2026-03-03 14:59:58', 'http://127.0.0.1:9000/farm/1772520721537_测试文件.gcode', 0, 'ABS', 1.20);
INSERT INTO `farm_print_job` VALUES (2, 21, NULL, 1, 0, 'QUEUED', 0.00, NULL, NULL, NULL, '2026-03-04 14:26:24', '2026-03-04 14:26:24', 'http://127.0.0.1:9000/farm/1772604929193_立方体_0.7mm_ABS_Generic Klipper Printer_56m0s.gcode', 3360, 'ABS', 1.20);
INSERT INTO `farm_print_job` VALUES (3, 21, NULL, 1, 1, 'QUEUED', 0.00, NULL, NULL, NULL, '2026-03-04 14:34:55', '2026-03-04 14:34:55', 'http://127.0.0.1:9000/farm/1772604929193_立方体_0.7mm_ABS_Generic Klipper Printer_56m0s.gcode', 3360, 'ABS', 1.20);

-- ----------------------------
-- Table structure for farm_printer
-- ----------------------------
DROP TABLE IF EXISTS `farm_printer`;
CREATE TABLE `farm_printer`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '打印机名称（如：Voron-2.4-01）',
  `ip_address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '局域网 IP 地址（NULL 表示设备当前未分配 IP 或已下线）',
  `mac_address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'MAC 地址（用于网络唤醒等）',
  `firmware_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'KLIPPER' COMMENT '固件类型（KLIPPER, RRF）',
  `api_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '上位机 API 通信密钥',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'OFFLINE' COMMENT '业务状态：OFFLINE, IDLE, PREPARING, PRINTING, PAUSED, ERROR, UNKNOWN',
  `current_job_id` bigint NULL DEFAULT NULL COMMENT '当前正在执行的打印任务 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `current_material` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '当前装载耗材',
  `nozzle_size` decimal(3, 2) NULL DEFAULT NULL COMMENT '当前安装的喷嘴直径',
  `grid_row` int NULL DEFAULT NULL COMMENT '物理网格所在行 (1-4)',
  `grid_col` int NULL DEFAULT NULL COMMENT '物理网格所在列 (1-12)',
  `machine_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户自定义的机器编号',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_ip_address`(`ip_address` ASC) USING BTREE,
  UNIQUE INDEX `uk_mac_address`(`mac_address` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_ip_address`(`ip_address` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 563 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '设备资产与状态表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of farm_printer
-- ----------------------------
INSERT INTO `farm_printer` VALUES (311, 'Printer_D5FC', '192.168.0.119', '9c:b8:b4:40:d5:fc', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-11 05:38:07', 'ABS', 0.40, 1, 2, 'A-02');
INSERT INTO `farm_printer` VALUES (312, 'Printer_3682', '192.168.0.129', '9c:b8:b4:41:36:82', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-12 15:48:39', 'ABS', 0.40, 2, 8, 'B-08');
INSERT INTO `farm_printer` VALUES (313, 'Printer_4CA8', '192.168.0.131', '54:78:c9:94:4c:a8', 'Klipper', NULL, 'PRINTING', NULL, '2026-03-05 13:19:13', '2026-03-13 10:14:37', 'ABS', 0.40, 3, 2, 'C-02');
INSERT INTO `farm_printer` VALUES (314, 'Printer_B136', '192.168.0.138', '9c:b8:b4:71:b1:36', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-11 18:27:25', 'ABS', 0.40, 1, 7, 'A-07');
INSERT INTO `farm_printer` VALUES (315, 'Printer_C1A2', '192.168.0.140', '9c:b8:b4:71:c1:a2', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-05 13:19:13', '2026-03-12 14:52:57', 'ABS', 0.40, 1, 10, 'A-10');
INSERT INTO `farm_printer` VALUES (316, 'Printer_2CE6', '192.168.0.141', '54:78:c9:94:2c:e6', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-11 18:27:26', 'ABS', 0.40, 1, 6, 'A-06');
INSERT INTO `farm_printer` VALUES (317, 'Printer_80BA', '192.168.0.144', '9c:b8:b4:71:80:ba', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-11 18:27:26', 'ABS', 0.40, 1, 11, 'A-11');
INSERT INTO `farm_printer` VALUES (318, 'Printer_817E', '192.168.0.145', '9c:b8:b4:71:81:7e', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-13 09:49:42', 'ABS', 0.40, 1, 8, 'A-08');
INSERT INTO `farm_printer` VALUES (319, 'Printer_E69E', '192.168.0.146', '9c:b8:b4:40:e6:9e', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-11 15:27:22', 'ABS', 0.40, 1, 5, 'A-05');
INSERT INTO `farm_printer` VALUES (320, 'Printer_9104', '192.168.0.147', '9c:b8:b4:71:91:04', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-11 06:38:12', 'ABS', 0.40, 1, 4, 'A-04');
INSERT INTO `farm_printer` VALUES (321, 'Printer_E1AC', '192.168.0.149', '9c:b8:b4:71:e1:ac', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-12 18:48:48', 'ABS', 0.40, 1, 9, 'A-09');
INSERT INTO `farm_printer` VALUES (322, 'Printer_B2D4', '192.168.0.151', '54:78:c9:93:b2:d4', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-05 13:19:13', '2026-03-09 11:44:08', 'ABS', 0.40, 1, 3, 'A-03');
INSERT INTO `farm_printer` VALUES (323, 'Printer_A0A6', '192.168.0.153', '9c:b8:b4:71:a0:a6', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-09 11:42:45', 'ABS', 0.40, 1, 1, 'A-01');
INSERT INTO `farm_printer` VALUES (324, 'Printer_B2FC', '192.168.0.181', '54:78:c9:93:b2:fc', 'Klipper', NULL, 'PRINTING', NULL, '2026-03-05 13:19:13', '2026-03-13 14:23:27', 'ABS', 0.40, 4, 5, 'D-05');
INSERT INTO `farm_printer` VALUES (325, 'Printer_2718', '192.168.0.157', '9c:b8:b4:41:27:18', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-09 14:33:30', 'ABS', 0.40, 4, 6, 'D-06');
INSERT INTO `farm_printer` VALUES (326, 'Printer_806A', '192.168.0.159', '54:78:c9:93:80:6a', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-13 13:28:47', 'ABS', 0.40, 3, 1, 'C-01');
INSERT INTO `farm_printer` VALUES (327, 'Printer_D0AA', '192.168.0.160', '9c:b8:b4:71:d0:aa', 'Klipper', NULL, 'PRINTING', NULL, '2026-03-05 13:19:13', '2026-03-13 10:17:12', 'ABS', 0.40, 4, 1, 'D-01');
INSERT INTO `farm_printer` VALUES (328, 'Printer_7122', '192.168.0.167', '9c:b8:b4:71:71:22', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-11 00:37:57', 'ABS', 0.40, 4, 9, 'D-09');
INSERT INTO `farm_printer` VALUES (329, 'Printer_B164', '192.168.0.169', '9c:b8:b4:71:b1:64', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-05 13:19:13', '2026-03-13 10:05:35', 'ABS', 0.40, 1, 12, 'A-12');
INSERT INTO `farm_printer` VALUES (330, 'Printer_2628', '192.168.0.173', '9c:b8:b4:41:26:28', 'Klipper', NULL, 'PRINTING', NULL, '2026-03-05 13:19:13', '2026-03-13 14:06:07', 'ABS', 0.40, NULL, NULL, NULL);
INSERT INTO `farm_printer` VALUES (331, 'Printer_26B0', '192.168.0.174', '9c:b8:b4:41:26:b0', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-13 13:48:47', 'ABS', 0.40, 4, 2, 'D-02');
INSERT INTO `farm_printer` VALUES (332, 'Printer_36BE', '192.168.0.177', '9c:b8:b4:41:36:be', 'Klipper', NULL, 'IDLE', NULL, '2026-03-05 13:19:13', '2026-03-13 10:45:27', 'ABS', 0.40, 3, 5, 'C-05');
INSERT INTO `farm_printer` VALUES (333, 'Printer_D654', '192.168.0.180', '9c:b8:b4:40:d6:54', 'Klipper', NULL, 'PRINTING', NULL, '2026-03-05 14:50:35', '2026-03-13 14:04:57', 'ABS', 0.40, 4, 4, 'D-04');
INSERT INTO `farm_printer` VALUES (390, 'Printer_80A0', '192.168.0.152', '9c:b8:b4:71:80:a0', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:02', '2026-03-11 17:27:24', 'ABS', 0.40, 3, 7, 'C-07');
INSERT INTO `farm_printer` VALUES (392, 'Printer_A146', '192.168.0.154', '9c:b8:b4:71:a1:46', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:02', '2026-03-13 08:49:39', 'ABS', 0.40, 2, 1, 'B-01');
INSERT INTO `farm_printer` VALUES (394, 'Printer_B084', '192.168.0.158', '9c:b8:b4:71:b0:84', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:03', '2026-03-13 08:49:39', 'ABS', 0.40, 2, 2, 'B-02');
INSERT INTO `farm_printer` VALUES (397, 'Printer_8098', '192.168.0.161', '9c:b8:b4:71:80:98', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:03', '2026-03-11 04:38:07', 'ABS', 0.40, 2, 11, 'B-11');
INSERT INTO `farm_printer` VALUES (398, 'Printer_7154', '192.168.0.162', '9c:b8:b4:71:71:54', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:03', '2026-03-11 19:27:31', 'ABS', 0.40, 2, 12, 'B-12');
INSERT INTO `farm_printer` VALUES (399, 'Printer_B10E', '192.168.0.163', '9c:b8:b4:71:b1:0e', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:03', '2026-03-11 16:36:41', 'ABS', 0.40, NULL, NULL, NULL);
INSERT INTO `farm_printer` VALUES (400, 'Printer_4660', '192.168.0.165', '9c:b8:b4:41:46:60', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-09 11:07:03', '2026-03-11 16:51:27', 'ABS', 0.40, 2, 5, 'B-05');
INSERT INTO `farm_printer` VALUES (401, 'Printer_D082', '192.168.0.166', '9c:b8:b4:71:d0:82', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:03', '2026-03-11 09:38:27', 'ABS', 0.40, 3, 11, 'C-11');
INSERT INTO `farm_printer` VALUES (403, 'Printer_C0DA', '192.168.0.168', '9c:b8:b4:71:c0:da', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-09 11:07:03', '2026-03-11 16:12:54', 'ABS', 0.40, 2, 6, 'B-06');
INSERT INTO `farm_printer` VALUES (405, 'Printer_C0BC', '192.168.0.170', '9c:b8:b4:71:c0:bc', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:03', '2026-03-09 11:50:56', 'ABS', 0.40, 2, 7, 'B-07');
INSERT INTO `farm_printer` VALUES (406, 'Printer_16F6', '192.168.0.171', '9c:b8:b4:41:16:f6', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:03', '2026-03-13 09:49:43', 'ABS', 0.40, 2, 9, 'B-09');
INSERT INTO `farm_printer` VALUES (409, 'Printer_368A', '192.168.0.175', '9c:b8:b4:41:36:8a', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-09 11:07:03', '2026-03-13 11:49:56', 'ABS', 0.40, 3, 3, 'C-03');
INSERT INTO `farm_printer` VALUES (410, 'Printer_26BE', '192.168.0.176', '9c:b8:b4:41:26:be', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:07:03', '2026-03-10 11:37:12', 'ABS', 0.40, 4, 3, 'D-03');
INSERT INTO `farm_printer` VALUES (446, 'Printer_4C8C', '192.168.0.178', '54:78:c9:94:4c:8c', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:20:59', '2026-03-09 11:40:20', 'ABS', 0.40, 2, 4, 'B-04');
INSERT INTO `farm_printer` VALUES (484, 'Printer_D773', '192.168.0.139', 'dc:84:03:e8:d7:73', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-09 11:24:13', '2026-03-09 19:40:05', 'ABS', 0.40, NULL, NULL, NULL);
INSERT INTO `farm_printer` VALUES (496, 'Printer_D12E', '192.168.0.156', '9c:b8:b4:71:d1:2e', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:24:13', '2026-03-10 23:37:52', 'ABS', 0.40, 2, 10, 'B-10');
INSERT INTO `farm_printer` VALUES (504, 'Printer_3698', '192.168.0.164', '9c:b8:b4:41:36:98', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:24:13', '2026-03-11 19:27:31', 'ABS', 0.40, 3, 12, 'C-12');
INSERT INTO `farm_printer` VALUES (533, 'Printer_3BF4', '192.168.0.155', '54:78:c9:94:3b:f4', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:37:47', '2026-03-11 16:27:24', 'ABS', 0.40, 2, 3, 'B-03');
INSERT INTO `farm_printer` VALUES (558, 'Printer_3C8C', '192.168.0.182', '54:78:c9:94:3c:8c', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:37:47', '2026-03-12 19:48:54', 'ABS', 0.40, 3, 6, 'C-06');
INSERT INTO `farm_printer` VALUES (559, 'Printer_71AC', '192.168.0.183', '9c:b8:b4:71:71:ac', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:37:47', '2026-03-13 14:01:43', 'ABS', 0.40, 3, 8, 'C-08');
INSERT INTO `farm_printer` VALUES (560, 'Printer_8FD6', '192.168.0.184', '54:78:c9:93:8f:d6', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-09 11:37:47', '2026-03-12 13:48:35', 'ABS', 0.40, 3, 9, 'C-09');
INSERT INTO `farm_printer` VALUES (561, 'Printer_A078', '192.168.0.185', '9c:b8:b4:71:a0:78', 'Klipper', NULL, 'IDLE', NULL, '2026-03-09 11:37:47', '2026-03-13 11:49:53', 'ABS', 0.40, 3, 10, 'C-10');
INSERT INTO `farm_printer` VALUES (562, 'Printer_80B2', '192.168.0.186', '9c:b8:b4:71:80:b2', 'Klipper', NULL, 'OFFLINE', NULL, '2026-03-09 11:37:47', '2026-03-09 13:54:55', 'ABS', 0.40, NULL, NULL, NULL);

-- ----------------------------
-- Table structure for farm_user
-- ----------------------------
DROP TABLE IF EXISTS `farm_user`;
CREATE TABLE `farm_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录账号',
  `password_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '加密后的密码',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'OPERATOR' COMMENT '角色权限：ADMIN, OPERATOR',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '手机号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '农场用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of farm_user
-- ----------------------------
INSERT INTO `farm_user` VALUES (1, 'admin', '$2a$10$KwtwJhEjIizaBlbl09U2Q.QZm3WL8eGTlmEhol88mhOpfAnxt/qh6', 'ADMIN', NULL, NULL, '2026-03-01 14:54:42', '2026-03-01 14:54:42');
INSERT INTO `farm_user` VALUES (2, 'user', '$2a$10$4HGt01EtACUq/KPjDybMS.U06MNRY.Zt8gSZcUhT0mNhuIK6CdQIK', 'OPERATOR', 'codexiang@vip.qq.com', '17628236031', '2026-03-04 15:04:42', '2026-03-04 15:04:42');

SET FOREIGN_KEY_CHECKS = 1;
