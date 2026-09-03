-- 手工执行脚本：修复任务指向不存在打印机的历史幽灵绑定。
-- 执行前必须备份数据库；脚本只解除孤儿任务的 printer_id，不会自动重新派单或调用设备。
START TRANSACTION;

CREATE TABLE IF NOT EXISTS farm_binding_repair_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repair_type VARCHAR(64) NOT NULL,
    job_id BIGINT NULL,
    printer_id BIGINT NULL,
    old_job_status VARCHAR(32) NULL,
    old_printer_id BIGINT NULL,
    reason VARCHAR(255) NOT NULL,
    repaired_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_binding_repair_job (job_id),
    KEY idx_binding_repair_printer (printer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO farm_binding_repair_audit
    (repair_type, job_id, printer_id, old_job_status, old_printer_id, reason)
SELECT
    'ORPHAN_JOB_PRINTER', j.id, NULL, j.status, j.printer_id,
    '任务绑定的打印机记录不存在，解除绑定并转入人工核对'
FROM farm_print_job j
LEFT JOIN farm_printer p ON p.id = j.printer_id
WHERE j.printer_id IS NOT NULL AND p.id IS NULL;

UPDATE farm_print_job j
LEFT JOIN farm_printer p ON p.id = j.printer_id
SET j.printer_id = NULL,
    j.status = CASE
        WHEN j.status IN ('ASSIGNED', 'READY', 'PRINTING', 'PAUSED') THEN 'RECONCILING'
        ELSE j.status
    END,
    j.error_reason = '历史打印机绑定不存在，已解除绑定，需人工重新核对后处理',
    j.updated_at = CURRENT_TIMESTAMP
WHERE j.printer_id IS NOT NULL AND p.id IS NULL;

COMMIT;
