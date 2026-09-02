-- 将旧版本任务状态迁移到当前统一状态。
-- 该项目没有 Flyway；已有 Docker 数据卷升级时请先备份，再手动执行本脚本。
-- 脚本可重复执行，不会影响已经是新状态的任务。

UPDATE farm_print_job
SET status = 'QUEUED'
WHERE status IN ('PENDING', 'MANUAL');

UPDATE farm_print_job
SET status = 'CANCELLED'
WHERE status = 'CANCELED';

ALTER TABLE farm_print_job
    MODIFY COLUMN status VARCHAR(20) NOT NULL
        COMMENT '任务状态：QUEUED, ASSIGNED, READY, PRINTING, PAUSED, COMPLETED, FAILED, CANCELLED';
