package com.example.farm.common.utils;

import com.example.farm.common.constant.CacheKeyConstant;
import com.example.farm.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

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
 * <p>设备操作在单个 Server 进程内互斥；数据库实例锁防止多个进程使用同一数据。</p>
 */
@Slf4j
public final class PrinterLock {

    public static final class BusyException extends BusinessException {
        public BusyException() { super(409, "设备操作繁忙，请稍后重试"); }
    }

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

    /** 在即将执行下一阶段动作前检查租约是否仍有效。 */
    public static void ensureValid(Long printerId) {
        Lease lease = printerId == null ? null : HELD_LOCKS.get().get(printerId);
        if (lease != null && lease.lost) {
            throw new BusinessException(503, "打印机锁续租失败，设备状态需要人工核对");
        }
    }

    public static <T> T execute(Long printerId, CacheStore cacheStore, Supplier<T> action) {
        return execute(printerId, cacheStore, action, 0);
    }

    private static <T> T execute(Long printerId, CacheStore cacheStore, Supplier<T> action, long lockWaitMillis) {
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
            String lockKey = CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_LOCK, printerId);
            String lockValue = UUID.randomUUID().toString();
            if (cacheStore != null) {
                boolean acquired;
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockWaitMillis);
                do {
                    try {
                        acquired = cacheStore.tryLock(lockKey, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                    } catch (RuntimeException exception) {
                        throw new BusinessException(503, "打印机锁服务暂不可用，请稍后重试");
                    }
                    if (acquired || System.nanoTime() >= deadline) break;
                    try {
                        TimeUnit.MILLISECONDS.sleep(50);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(503, "等待打印机锁时被中断");
                    }
                } while (true);
                if (!acquired) {
                    throw new BusyException();
                }
                lease = new Lease(printerId, lockKey, lockValue, cacheStore);
                lease.renewTask = RENEWER.scheduleAtFixedRate(lease::renew,
                        RENEW_INTERVAL_SECONDS, RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
            } else {
                lease = new Lease(printerId, null, null, null);
            }
            HELD_LOCKS.get().put(printerId, lease);
            return action.get();
        } finally {
            try {
                if (lease != null) {
                    HELD_LOCKS.get().remove(printerId);
                    lease.close();
                }
            } finally {
                localLock.unlock();
            }
            // 已取得锁引用但尚未调用 lock() 的请求不在队列中；保留每台设备的
            // 进程内互斥锁，避免该窗口内另一个请求创建第二把锁。
        }
    }

    /** 短暂等待本进程中的操作完成，避免轮询导致删除立即失败。 */
    public static <T> T tryExecute(Long printerId, CacheStore cacheStore, Supplier<T> action) {
        return tryExecute(printerId, cacheStore, action, 500);
    }

    public static <T> T tryExecute(Long printerId, CacheStore cacheStore, Supplier<T> action, long waitMillis) {
        requirePrinterId(printerId);
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(printerId, ignored -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = localLock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "等待打印机锁时被中断");
        }
        if (!acquired) {
            throw new BusyException();
        }
        try {
            return execute(printerId, cacheStore, action, waitMillis);
        } finally {
            localLock.unlock();
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
        private final CacheStore cacheStore;
        private volatile boolean lost;
        private volatile int depth = 1;
        private volatile java.util.concurrent.ScheduledFuture<?> renewTask;

        private Lease(Long printerId, String lockKey, String lockValue, CacheStore cacheStore) {
            this.printerId = printerId;
            this.lockKey = lockKey;
            this.lockValue = lockValue;
            this.cacheStore = cacheStore;
        }

        private void renew() {
            if (cacheStore == null || lost) {
                return;
            }
            try {
                if (!cacheStore.renewLockIfOwned(lockKey, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS)) {
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
            if (cacheStore != null && !lost) {
                try {
                    cacheStore.releaseLockIfOwned(lockKey, lockValue);
                } catch (RuntimeException exception) {
                    // 操作已完成，释放故障不能误报为未提交；租约会自然过期。
                    log.warn("打印机锁释放失败，等待租约过期: printerId={}", printerId);
                }
            }
        }
    }
}
