-- TC-003：Local SQLite 任务查询状态回填及分页查询索引。
-- LocalSchemaMigration 会在已有库启动时执行同样的幂等步骤；本脚本供手工核对/升级使用。
-- 已有 LocalDateTime 不回写；查询和应用写入均使用 Asia/Shanghai wall-clock。

UPDATE farm_print_job
SET status = 'QUEUED'
WHERE status IN ('PENDING', 'MANUAL');

UPDATE farm_print_job
SET status = 'CANCELLED'
WHERE status = 'CANCELED';

CREATE INDEX IF NOT EXISTS idx_print_job_history_status_completed_id
    ON farm_print_job(status, completed_at, id);

CREATE INDEX IF NOT EXISTS idx_print_job_active_status_created_id
    ON farm_print_job(status, created_at, id);
