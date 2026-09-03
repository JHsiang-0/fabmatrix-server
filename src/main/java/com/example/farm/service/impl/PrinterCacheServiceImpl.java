package com.example.farm.service.impl;

import com.example.farm.common.constant.RedisKeyConstant;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterStatusHistoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 打印机缓存服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterCacheServiceImpl implements PrinterCacheService {

    private final RedisUtil redisUtil;
    private final PrinterMapper printerMapper;
    private final PrinterStatusHistoryService printerStatusHistoryService;

    // 缓存过期时间：10秒
    private static final long STATUS_CACHE_TTL = 10;
    // 分布式锁过期时间：5秒
    private static final long LOCK_TTL = 5;
    // 历史记录保留数量
    private static final int HISTORY_KEEP_COUNT = 2880;
    // 心跳超时时间：30秒
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 30;
    // 持久化状态历史的最小采样间隔；状态发生变化时立即记录。
    private static final long PERSIST_HISTORY_INTERVAL_MILLIS = 60_000;
    private final Map<Long, PersistedStatus> lastPersistedStatuses = new ConcurrentHashMap<>();

    @Override
    public void cachePrinterStatus(Long printerId, MoonrakerStatusDTO status) {
        if (printerId == null || status == null) return;
        
        String key = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_STATUS, printerId);
        redisUtil.set(key, status, STATUS_CACHE_TTL, TimeUnit.SECONDS);
    }

    @Override
    public MoonrakerStatusDTO getCachedStatus(Long printerId) {
        if (printerId == null) return null;
        
        String key = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_STATUS, printerId);
        return redisUtil.get(key, MoonrakerStatusDTO.class);
    }

    @Override
    public List<MoonrakerStatusDTO> getCachedStatusBatch(List<Long> printerIds) {
        if (printerIds == null || printerIds.isEmpty()) {
            return new ArrayList<>();
        }
        return printerIds.stream()
                .map(this::getCachedStatus)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public void clearStatusCache(Long printerId) {
        if (printerId == null) return;
        
        String key = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_STATUS, printerId);
        redisUtil.delete(key);
    }

    @Override
    public boolean updatePrinterStatusWithLock(Printer printer) {
        if (printer == null || printer.getId() == null) return false;

        Long printerId = printer.getId();
        String lockKey = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_LOCK, printerId);
        String lockValue = UUID.randomUUID().toString();

        boolean locked = false;
        try {
            locked = redisUtil.tryLock(lockKey, lockValue, LOCK_TTL, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("更新打印机状态时未获取到分布式锁: printerId={}", printerId);
                return false;
            }

            boolean updated = printerMapper.updateById(printer) > 0;
            if (updated) {
                // 状态也属于打印机列表快照的一部分，避免列表缓存继续暴露旧状态。
                refreshPrinterCache();
            }
            return updated;

        } catch (Exception e) {
            log.error("更新打印机状态到数据库失败: printerId={}", printerId, e);
            return false;
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

    @Override
    public boolean tryLockPrinterStatus(Long printerId) {
        String lockKey = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_LOCK, printerId);
        String lockValue = UUID.randomUUID().toString();
        return redisUtil.tryLock(lockKey, lockValue, LOCK_TTL, TimeUnit.SECONDS);
    }

    @Override
    public void unlockPrinterStatus(Long printerId) {
        String lockKey = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_LOCK, printerId);
        redisUtil.unlock(lockKey);
    }

    @Override
    public void recordStatusHistory(Long printerId, MoonrakerStatusDTO status) {
        if (printerId == null || status == null) return;
        
        try {
            String key = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_HISTORY, printerId);
            redisUtil.listLeftPush(key, status);
            redisUtil.listTrim(key, 0, HISTORY_KEEP_COUNT - 1);
            redisUtil.expire(key, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("写入打印机状态历史失败: printerId={}", printerId, e);
        }

        // Redis 保留高频短期数据；MySQL 只保存状态变化或每分钟一次的样本，
        // 既支持分页和重启后查询，也避免每台设备每5秒产生一条持久化记录。
        PersistedStatus current = PersistedStatus.from(status);
        PersistedStatus previous = lastPersistedStatuses.get(printerId);
        long now = System.currentTimeMillis();
        if (previous == null || now - previous.recordedAt() >= PERSIST_HISTORY_INTERVAL_MILLIS
                || !current.sameState(previous)) {
            if (printerStatusHistoryService.record(printerId, status)) {
                lastPersistedStatuses.put(printerId, current.withRecordedAt(now));
            } else {
                log.warn("打印机状态历史持久化未成功，将在后续采样重试: printerId={}", printerId);
            }
        }
    }

    @Override
    public List<MoonrakerStatusDTO> getStatusHistory(Long printerId, int count) {
        if (printerId == null || count <= 0) {
            return new ArrayList<>();
        }
        try {
            String key = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_HISTORY, printerId);
            return redisUtil.listRange(key, 0, count - 1, MoonrakerStatusDTO.class);
        } catch (Exception e) {
            log.error("读取打印机状态历史失败: printerId={}, count={}", printerId, count, e);
            return new ArrayList<>();
        }
    }

    @Override
    public long getOnlinePrinterCount() {
        try {
            cleanupExpiredHeartbeats();
            Long count = redisUtil.zSetSize(RedisKeyConstant.PRINTER_HEARTBEAT_ZSET);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("获取在线打印机数量失败", e);
            return 0;
        }
    }

    @Override
    public long getBusyPrinterCount() {
        try {
            Long count = redisUtil.setSize(RedisKeyConstant.PRINTER_BUSY_SET);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("获取忙碌打印机数量失败", e);
            return 0;
        }
    }

    @Override
    public void markPrinterOnline(Long printerId) {
        if (printerId == null) return;
        
        try {
            redisUtil.zSetAdd(RedisKeyConstant.PRINTER_HEARTBEAT_ZSET, 
                    printerId.toString(), System.currentTimeMillis());
        } catch (Exception e) {
            log.error("标记打印机在线失败: printerId={}", printerId, e);
        }
    }

    @Override
    public void markPrinterOffline(Long printerId) {
        if (printerId == null) return;
        
        try {
            redisUtil.zSetRemove(RedisKeyConstant.PRINTER_HEARTBEAT_ZSET, printerId.toString());
            redisUtil.setRemove(RedisKeyConstant.PRINTER_BUSY_SET, printerId.toString());
        } catch (Exception e) {
            log.error("标记打印机离线失败: printerId={}", printerId, e);
        }
    }

    @Override
    public void markPrinterBusy(Long printerId) {
        if (printerId == null) return;
        
        try {
            redisUtil.setAdd(RedisKeyConstant.PRINTER_BUSY_SET, printerId.toString());
        } catch (Exception e) {
            log.error("标记打印机忙碌失败: printerId={}", printerId, e);
        }
    }

    @Override
    public void markPrinterIdle(Long printerId) {
        if (printerId == null) return;
        
        try {
            redisUtil.setRemove(RedisKeyConstant.PRINTER_BUSY_SET, printerId.toString());
        } catch (Exception e) {
            log.error("标记打印机空闲失败: printerId={}", printerId, e);
        }
    }

    private void cleanupExpiredHeartbeats() {
        try {
            long cutoffTime = System.currentTimeMillis() - (HEARTBEAT_TIMEOUT_SECONDS * 1000);
            redisUtil.zSetRemoveRangeByScore(RedisKeyConstant.PRINTER_HEARTBEAT_ZSET, 0, cutoffTime);
        } catch (Exception e) {
            log.error("清理过期打印机心跳失败", e);
        }
    }

    @Override
    public void cacheSystemStats(Object stats) {
        if (stats == null) return;
        
        String key = RedisKeyConstant.getKey(RedisKeyConstant.SYSTEM_STATS, "overview");
        redisUtil.set(key, stats, 60, TimeUnit.SECONDS);
    }

    @Override
    public Object getSystemStats() {
        String key = RedisKeyConstant.getKey(RedisKeyConstant.SYSTEM_STATS, "overview");
        return redisUtil.get(key, Object.class);
    }

    @Override
    public List<Printer> getAllPrintersFromCache() {
        String key = RedisKeyConstant.PRINTER_LIST;
        return redisUtil.get(key, new TypeReference<List<Printer>>() {});
    }

    @Override
    public void cacheAllPrinters(List<Printer> printers) {
        if (printers == null || printers.isEmpty()) {
            log.warn("缓存打印机列表时跳过空列表");
            return;
        }
        
        String key = RedisKeyConstant.PRINTER_LIST;
        redisUtil.set(key, printers, 1, TimeUnit.HOURS);
        log.info("打印机列表缓存完成: {} 台", printers.size());
    }

    @Override
    public void refreshPrinterCache() {
        String key = RedisKeyConstant.PRINTER_LIST;
        redisUtil.delete(key);
        log.info("已刷新打印机列表缓存");
    }

    private record PersistedStatus(String unifiedState, String rawState,
                                   String systemMessage, long recordedAt) {

        private static PersistedStatus from(MoonrakerStatusDTO status) {
            return new PersistedStatus(status.getUnifiedState(), status.getState(),
                    status.getSystemMessage(), 0);
        }

        private PersistedStatus withRecordedAt(long timestamp) {
            return new PersistedStatus(unifiedState, rawState, systemMessage, timestamp);
        }

        private boolean sameState(PersistedStatus other) {
            return Objects.equals(unifiedState, other.unifiedState)
                    && Objects.equals(rawState, other.rawState)
                    && Objects.equals(systemMessage, other.systemMessage);
        }
    }
}
