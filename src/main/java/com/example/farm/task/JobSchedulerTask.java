package com.example.farm.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.farm.common.constant.RedisKeyConstant;
import com.example.farm.common.utils.LogUtil;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrintJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 农场大脑：自动化任务调度器
 * 使用分布式锁确保集群环境下只有一个实例执行调度
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "farm.tasks", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobSchedulerTask {

    private final PrintJobService printJobService;
    private final PrinterService printerService;
    private final RedisUtil redisUtil;

    // 分布式锁过期时间：10秒
    private static final long LOCK_TTL = 10;
    // 慢调度阈值：3秒
    private static final long SLOW_THRESHOLD_MS = 3000;

    /**
     * 每10秒扫描一次队列进行自动派单
     */
    @Scheduled(fixedRate = 10000)
    public void scheduleJobs() {
        String lockKey = RedisKeyConstant.SCHEDULER_LOCK;
        String lockValue = UUID.randomUUID().toString();

        boolean locked = false;
        try {
            locked = redisUtil.tryLock(lockKey, lockValue, LOCK_TTL, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("调度锁已被其他实例持有，跳过本轮任务调度");
                return;
            }

            doSchedule();
        } finally {
            if (locked) {
                releaseLock(lockKey, lockValue);
            }
        }
    }

    private void releaseLock(String lockKey, String lockValue) {
        String currentValue = redisUtil.getLockValue(lockKey);
        if (lockValue.equals(currentValue)) {
            redisUtil.unlock(lockKey);
        }
    }

    public void doSchedule() {
        long startTime = System.currentTimeMillis();
        
        try {
            // 获取空闲打印机
            List<Printer> idlePrinters = printerService.list(new LambdaQueryWrapper<Printer>()
                    .eq(Printer::getStatus, "IDLE"));

            if (idlePrinters.isEmpty()) {
                return;
            }

            // 获取排队任务
            List<PrintJob> queuedJobs = printJobService.getQueuedJobs();

            if (queuedJobs.isEmpty()) {
                return;
            }

            log.info("调度扫描结果: 空闲打印机 {} 台，待派发任务 {} 个", 
                    idlePrinters.size(), queuedJobs.size());

            // 开始配对
            int assignCount = Math.min(idlePrinters.size(), queuedJobs.size());
            int successCount = 0;

            for (int i = 0; i < assignCount; i++) {
                if (tryAssignJob(queuedJobs.get(i), idlePrinters.get(i))) {
                    successCount++;
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            if (successCount > 0) {
                LogUtil.bizInfo("JOB_ASSIGN", "assigned", successCount, "total", assignCount, "durationMs", duration);
            }
            
            LogUtil.slowOperation("scheduleJobs", duration, SLOW_THRESHOLD_MS);
            
        } catch (Exception e) {
            log.error("任务调度执行失败", e);
        }
    }

    private boolean tryAssignJob(PrintJob job, Printer printer) {
        Long jobId = job.getId();
        String lockKey = RedisKeyConstant.getKey(RedisKeyConstant.JOB_LOCK, jobId);
        String lockValue = UUID.randomUUID().toString();

        boolean locked = false;
        try {
            locked = redisUtil.tryLock(lockKey, lockValue, 30, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("任务已被其他调度器锁定，跳过: jobId={}", jobId);
                return false;
            }

            // 重新查询最新状态
            PrintJob currentJob = printJobService.getById(jobId);
            if (currentJob == null || !PrintJobStatus.QUEUED.name().equals(PrintJobStatus.normalize(currentJob.getStatus()))) {
                log.debug("任务状态已变化，跳过派发: jobId={}", jobId);
                return false;
            }

            Printer currentPrinter = printerService.getById(printer.getId());
            if (currentPrinter == null || !"IDLE".equals(currentPrinter.getStatus())) {
                log.debug("打印机状态已变化，跳过派发: printerId={}, name={}", printer.getId(), printer.getName());
                return false;
            }

            try {
                return printJobService.assignQueuedJob(currentJob.getId(), currentPrinter.getId());
            } catch (Exception e) {
                log.error("派发任务失败: jobId={}, printerId={}", jobId, currentPrinter.getId(), e);
                return false;
            }

        } finally {
            if (locked) {
                releaseLock(lockKey, lockValue);
            }
        }
    }

}
