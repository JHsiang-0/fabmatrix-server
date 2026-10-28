-- FabMatrix 1.0.0：已有 MySQL 库与当前初始化基线的剩余差异。
-- 执行前备份；先补齐所需 18/21/22 号迁移。本脚本可重复执行。
-- 不修改已有业务记录；已有用户的数据库必须永久关闭首次管理员入口。

CREATE TABLE IF NOT EXISTS farm_setup_state (
    id BIGINT NOT NULL,
    initialized TINYINT(1) NOT NULL DEFAULT 0,
    initialized_at DATETIME NULL,
    initialized_by BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_farm_setup_state_singleton CHECK (id = 1),
    CONSTRAINT ck_farm_setup_state_initialized CHECK (initialized IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO farm_setup_state (id, initialized)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM farm_setup_state WHERE id = 1);

UPDATE farm_setup_state
SET initialized = 1,
    initialized_at = COALESCE(initialized_at, CURRENT_TIMESTAMP)
WHERE id = 1 AND initialized = 0
  AND EXISTS (SELECT 1 FROM farm_user);

-- 仅设置新记录的默认值；不重写历史任务状态、字段类型或注释。
ALTER TABLE farm_print_job ALTER COLUMN status SET DEFAULT 'QUEUED';
