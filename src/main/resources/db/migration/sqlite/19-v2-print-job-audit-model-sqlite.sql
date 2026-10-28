-- TC-007：历史快照与重试审计模型（SQLite）。
-- SQLite 原生不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS。LocalSchemaMigration
-- 会在启动时按列名幂等补齐以下字段；本脚本用于已完成列补齐后的离线回填/索引核对，
-- 因而可重复执行。不会创建 farm_print_job 自引用外键。
-- 只能回填当前仍存在的资源；operator 仅以 started_at 非空作为实际开始证据。
-- 历史改名及已删除/丢失字段不可恢复，READY 且 started_at 为空不会推断 operator 快照。

UPDATE farm_print_job
SET root_job_id = id
WHERE root_job_id IS NULL;

UPDATE farm_print_job
SET attempt_number = 1
WHERE attempt_number IS NULL OR attempt_number < 1;

UPDATE farm_print_job
SET file_name_snapshot = COALESCE(file_name_snapshot,
        (SELECT original_name FROM farm_print_file f WHERE f.id = farm_print_job.file_id)),
    printer_name_snapshot = COALESCE(printer_name_snapshot,
        (SELECT name FROM farm_printer p WHERE p.id = farm_print_job.printer_id)),
    machine_number_snapshot = COALESCE(machine_number_snapshot,
        (SELECT machine_number FROM farm_printer p WHERE p.id = farm_print_job.printer_id)),
    creator_username_snapshot = COALESCE(creator_username_snapshot,
        (SELECT username FROM farm_user u WHERE u.id = farm_print_job.user_id)),
    operator_username_snapshot = CASE
        WHEN started_at IS NOT NULL THEN COALESCE(operator_username_snapshot,
            (SELECT username FROM farm_user u WHERE u.id = farm_print_job.operator_id))
        ELSE operator_username_snapshot
    END
WHERE file_name_snapshot IS NULL
   OR printer_name_snapshot IS NULL
   OR machine_number_snapshot IS NULL
   OR creator_username_snapshot IS NULL
   OR (started_at IS NOT NULL AND operator_username_snapshot IS NULL);

CREATE UNIQUE INDEX IF NOT EXISTS uk_print_job_retry_of_job
    ON farm_print_job(retry_of_job_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_print_job_root_attempt
    ON farm_print_job(root_job_id, attempt_number);
