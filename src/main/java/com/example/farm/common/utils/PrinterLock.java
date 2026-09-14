package com.example.farm.common.utils;

import com.example.farm.common.constant.RedisKeyConstant;
import com.example.farm.common.exception.BusinessException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 打印机控制边界锁。
 *
 * <p>Local Edition 依靠进程内锁保证单进程互斥，Server Edition 在此基础上使用
 * Redis 同名锁保证跨实例互斥。Redis 锁由守护续租线程延长，避免上传或协议调用
 * 超过固定 TTL 后被第二个请求插入。</p>
 */
public final class PrinterLock {

    private static final long LOCK_TTL_SECONDS = 60;
    private static final long RENEW_INTERVAL_SECONDS = 20;
    private static final Map<Long, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<Long, Lease>> HELD_LOCKS =
            ThreadLocal.withInitial(ConcurrentHashMap::new);
    private static final ScheduledExecutorService RENEWER = Executors.newScheduledThreadPool(
            1, runnable -> {
                Thread thread = new Thread(runnable, "farm-printer-lock-renewer");
                thread.setDaemon(true);
                return thread;
            });

    private PrinterLock() {
    }

    public static boolean isHeld(Long printerId) {
        return printerId != null && HELD_LOCKS.get().containsKey(printerId);
    }

    /** 在即将执行下一阶段动作前检查 Redis 租约是否仍由当前请求持有。 */
    public static void ensureValid(Long printerId) {
        Lease lease = printerId == null ? null : HELD_LOCKS.get().get(printerId);
        if (lease != null && lease.lost) {
            throw new BusinessException(503, "打印机锁续租失败，设备状态需要人工核对");
        }
    }

    public static <T> T execute(Long printerId, RedisUtil redisUtil, Supplier<T> action) {
        requirePrinterId(printerId);
        Lease existing = HELD_LOCKS.get().get(printerId);
        if (existing != null) {
            existing.depth++;
            try {
                return action.get();
            } finally {
                existing.depth--;
            }
        }

        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(printerId, ignored -> new ReentrantLock());
        localLock.lock();
        Lease lease = null;
        try {
            String lockKey = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_LOCK, printerId);
            String lockValue = UUID.randomUUID().toString();
            if (redisUtil != null) {
                boolean acquired;
                try {
                    acquired = redisUtil.tryLock(lockKey, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                } catch (RuntimeException exception) {
                    throw new BusinessException(503, "打印机锁服务暂不可用，请稍后重试");
                }
                if (!acquired) {
                    throw new BusinessException(409, "打印机正在被其他请求操作");
                }
                lease = new Lease(printerId, lockKey, lockValue, redisUtil);
                lease.renewTask = RENEWER.scheduleAtFixedRate(lease::renew,
                        RENEW_INTERVAL_SECONDS, RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
            } else {
                lease = new Lease(printerId, null, null, null);
            }
            HELD_LOCKS.get().put(printerId, lease);
            return action.get();
        } finally {
            if (lease != null) {
                HELD_LOCKS.get().remove(printerId);
                lease.close();
            }
            localLock.unlock();
            if (!localLock.hasQueuedThreads()) {
                LOCAL_LOCKS.remove(printerId, localLock);
            }
        }
    }

    private static void requirePrinterId(Long printerId) {
        if (printerId == null || printerId <= 0) {
            throw new BusinessException(400, "打印机 ID 必须为正数");
        }
    }

    private static final class Lease {
        private final Long printerId;
        private final String lockKey;
        private final String lockValue;
        private final RedisUtil redisUtil;
        private volatile boolean lost;
        private volatile int depth = 1;
        private volatile java.util.concurrent.ScheduledFuture<?> renewTask;

        private Lease(Long printerId, String lockKey, String lockValue, RedisUtil redisUtil) {
            this.printerId = printerId;
            this.lockKey = lockKey;
            this.lockValue = lockValue;
            this.redisUtil = redisUtil;
        }

        private void renew() {
            if (redisUtil == null || lost) {
                return;
            }
            try {
                if (!redisUtil.renewLockIfOwned(lockKey, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS)) {
                    lost = true;
                }
            } catch (RuntimeException exception) {
                lost = true;
            }
        }

        private void close() {
            if (renewTask != null) {
                renewTask.cancel(false);
            }
            if (redisUtil != null && !lost) {
                redisUtil.releaseLockIfOwned(lockKey, lockValue);
            }
        }
    }
}
