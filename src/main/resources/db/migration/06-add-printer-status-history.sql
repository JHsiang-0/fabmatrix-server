-- 打印机状态历史持久化表。
-- 本脚本只新增表，不删除或修改现有业务数据，可重复执行。
-- Redis 继续保存最近的高频状态；该表保存状态变化或每分钟一次的样本，供分页查询。

CREATE TABLE IF NOT EXISTS farm_printer_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    printer_id BIGINT NOT NULL COMMENT '打印机ID',
    status VARCHAR(30) NULL COMMENT '统一状态',
    raw_state VARCHAR(50) NULL COMMENT '设备原始状态',
    system_message VARCHAR(500) NULL COMMENT '系统状态消息',
    filename VARCHAR(500) NULL COMMENT '当前打印文件名',
    progress DECIMAL(6, 2) NULL COMMENT '打印进度（0-100）',
    tool_temperature DECIMAL(8, 2) NULL COMMENT '喷头当前温度',
    tool_target DECIMAL(8, 2) NULL COMMENT '喷头目标温度',
    bed_temperature DECIMAL(8, 2) NULL COMMENT '热床当前温度',
    bed_target DECIMAL(8, 2) NULL COMMENT '热床目标温度',
    print_duration DECIMAL(12, 2) NULL COMMENT '打印持续时间（秒）',
    total_duration DECIMAL(12, 2) NULL COMMENT '总持续时间（秒）',
    filament_used DECIMAL(14, 2) NULL COMMENT '已用耗材长度（毫米）',
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '状态记录时间',
    PRIMARY KEY (id),
    INDEX idx_printer_recorded_at (printer_id, recorded_at, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '打印机状态历史样本';
