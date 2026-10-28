-- 打印机监控可靠性：资产记录与后台监控目标、通信健康状态分离。
-- 执行前请备份已有数据库；本文件只新增字段和索引，不删除历史设备。
-- 本脚本使用 MySQL 8 元数据检查，可安全重复执行。

DELIMITER $$

DROP PROCEDURE IF EXISTS farm_add_printer_monitor_column_if_missing$$
CREATE PROCEDURE farm_add_printer_monitor_column_if_missing(
    IN p_column_name VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    DECLARE v_column_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND column_name = p_column_name;

    IF v_column_count = 0 THEN
        SET @farm_monitor_ddl = CONCAT(
            'ALTER TABLE `farm_printer` ADD COLUMN `', p_column_name, '` ', p_definition
        );
        PREPARE farm_monitor_stmt FROM @farm_monitor_ddl;
        EXECUTE farm_monitor_stmt;
        DEALLOCATE PREPARE farm_monitor_stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS farm_add_printer_monitor_index_if_missing$$
CREATE PROCEDURE farm_add_printer_monitor_index_if_missing()
BEGIN
    DECLARE v_index_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_index_count
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND index_name = 'idx_printer_monitor_enabled';

    IF v_index_count = 0 THEN
        ALTER TABLE `farm_printer`
            ADD INDEX `idx_printer_monitor_enabled` (`monitor_enabled`, `firmware_type`);
    END IF;
END$$

CALL farm_add_printer_monitor_column_if_missing(
    'monitor_enabled',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否纳入后台监控'''
)$$
CALL farm_add_printer_monitor_column_if_missing(
    'connection_status',
    'VARCHAR(16) NOT NULL DEFAULT ''UNKNOWN'' COMMENT ''通信健康状态'''
)$$
CALL farm_add_printer_monitor_column_if_missing(
    'last_seen_at',
    'DATETIME NULL COMMENT ''最近一次成功通信时间'''
)$$
CALL farm_add_printer_monitor_column_if_missing(
    'last_communication_error',
    'VARCHAR(255) NULL COMMENT ''最近一次通信错误摘要'''
)$$
CALL farm_add_printer_monitor_column_if_missing(
    'consecutive_failure_count',
    'INT NOT NULL DEFAULT 0 COMMENT ''连续通信失败次数'''
)$$
CALL farm_add_printer_monitor_column_if_missing(
    'last_known_status',
    'VARCHAR(32) NULL COMMENT ''最近一次已知设备业务状态'''
)$$
CALL farm_add_printer_monitor_column_if_missing(
    'last_protocol_state',
    'VARCHAR(32) NULL COMMENT ''最近一次协议原始状态'''
)$$
CALL farm_add_printer_monitor_column_if_missing(
    'last_device_uptime',
    'BIGINT NULL COMMENT ''最近一次设备运行时间（秒）'''
)$$
CALL farm_add_printer_monitor_index_if_missing()$$

DROP PROCEDURE IF EXISTS farm_add_printer_monitor_column_if_missing$$
DROP PROCEDURE IF EXISTS farm_add_printer_monitor_index_if_missing$$

DELIMITER ;
