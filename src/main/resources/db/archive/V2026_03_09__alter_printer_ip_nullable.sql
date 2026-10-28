-- ============================================
-- 修改打印机表：允许 ip_address 为 NULL
-- 原因：当设备下线或 IP 被其他设备占用时，需要将旧设备的 IP 设为 NULL
-- ============================================

ALTER TABLE farm_printer
    MODIFY COLUMN ip_address VARCHAR(50) NULL COMMENT '局域网 IP 地址';

-- 更新注释说明
ALTER TABLE farm_printer
    MODIFY COLUMN ip_address VARCHAR(50) NULL COMMENT '局域网 IP 地址（NULL 表示设备当前未分配 IP 或已下线）';
