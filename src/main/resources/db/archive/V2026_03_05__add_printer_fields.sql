-- ============================================
-- 3D 打印农场管理系统 - 数据库迁移脚本
-- 版本: V2026.03.05
-- 说明: 为已存在的 farm_printer 表添加 MAC Upsert 机制所需索引
--       适用于从旧版本升级的场景
-- ============================================

-- ----------------------------------------------------
-- 1. 确保 MAC 地址有唯一索引（Upsert 机制关键）
-- ----------------------------------------------------
-- 先检查是否已存在索引
SET @index_exists = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND index_name = 'uk_mac_address'
);

-- 如果不存在则创建
SET @sql = IF(@index_exists = 0,
              'ALTER TABLE farm_printer ADD UNIQUE INDEX uk_mac_address (mac_address);',
              'SELECT "Index uk_mac_address already exists" AS message;');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------
-- 2. 添加 IP 地址索引（用于快速冲突检测）
-- ----------------------------------------------------
SET @index_exists = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND index_name = 'idx_ip_address'
);

SET @sql = IF(@index_exists = 0,
              'ALTER TABLE farm_printer ADD INDEX idx_ip_address (ip_address);',
              'SELECT "Index idx_ip_address already exists" AS message;');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------
-- 3. 添加物理位置组合索引（数字孪生看板用）
-- ----------------------------------------------------
SET @index_exists = (
    SELECT COUNT(1)
    FROM information_schema.STATISTICS
    WHERE table_schema = DATABASE()
      AND table_name = 'farm_printer'
      AND index_name = 'idx_grid_position'
);

SET @sql = IF(@index_exists = 0,
              'ALTER TABLE farm_printer ADD INDEX idx_grid_position (grid_row, grid_col);',
              'SELECT "Index idx_grid_position already exists" AS message;');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------
-- 4. 验证索引创建成功
-- ----------------------------------------------------
SELECT 
    INDEX_NAME,
    COLUMN_NAME,
    NON_UNIQUE
FROM information_schema.STATISTICS
WHERE table_schema = DATABASE()
  AND table_name = 'farm_printer'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
