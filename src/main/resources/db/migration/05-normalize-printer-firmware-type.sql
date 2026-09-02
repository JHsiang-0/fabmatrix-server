-- 统一打印机协议类型的历史大小写值。
-- 仅转换当前支持的协议，不会把未知协议静默伪装成 Klipper。
UPDATE farm_printer
SET firmware_type = 'KLIPPER'
WHERE UPPER(TRIM(firmware_type)) = 'KLIPPER';

UPDATE farm_printer
SET firmware_type = 'RRF'
WHERE UPPER(TRIM(firmware_type)) = 'RRF';

ALTER TABLE farm_printer
    MODIFY COLUMN firmware_type VARCHAR(20) NOT NULL DEFAULT 'KLIPPER'
        COMMENT '固件类型（KLIPPER, RRF）';
