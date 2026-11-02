package com.example.farm.service.impl;

import com.example.farm.common.constant.CacheKeyConstant;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.CacheStore;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.protocol.observation.PrinterObservation;
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

    private final CacheStore cacheStore;
    private final PrinterMapper printerMapper;
    private final PrinterStatusHistoryService printerStatusHistoryService;

    // 缓存过期时间：10秒
    private static final long STATUS_CACHE_TTL = 10;
    private static final long OBSERVATION_CACHE_TTL = 90;
    // 分布式锁过期时间：5秒
    private static final long LOCK_TTL = 5;
    // 历史记录保留数量
    private static final int HISTORY_KEEP_COUNT = 2880;
    // 心跳超时时间：30秒
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 30;
    private final Map<Long, PersistedStatus> lastPersistedStatuses = new ConcurrentHashMap<>();

    @Override
    public void cachePrinterObservation(Long printerId, PrinterObservation observation) {
        if (printerId == null || observation == null) return;
        cacheStore.set(CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_OBSERVATION, printerId),
                observation, OBSERVATION_CACHE_TTL, TimeUnit.SECONDS);
    }

    @Override
    public PrinterObservation getCachedObservation(Long printerId) {
        if (printerId == null) return null;
        return cacheStore.get(CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_OBSERVATION, printerId),
                PrinterObservation.class);
    }

    @Override
    public void cachePrinterStatus(Long printerId, MoonrakerStatusDTO status) {
        if (printerId == null || status == null) return;
        
        String key = CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_STATUS, printerId);
        cacheStore.set(key, status, STATUS_CACHE_TTL, TimeUnit.SECONDS);
    }

    @Override
    public MoonrakerStatusDTO getCachedStatus(Long printerId) {
        if (printerId == null) return null;
        
        String key = CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_STATUS, printerId);
        return cacheStore.get(key, MoonrakerStatusDTO.class);
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
        
        String key = CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_STATUS, printerId);
        cacheStore.delete(key);
    }

    @Override
    public boolean updatePrinterStatusWithLock(Printer printer) {
        if (printer == null || printer.getId() == null) return false;

        Long printerId = printer.getId();
        try {
            return PrinterLock.execute(printerId, cacheStore, () -> {
                PrinterLock.ensureValid(printerId);
                boolean updated = printerMapper.updateById(printer) > 0;
                if (updated) {
                    // 状态也属于打印机列表快照的一部分，避免列表缓存继续暴露旧状态。
                    refreshPrinterCache();
                }
                return updated;
            });
        } catch (BusinessException e) {
            if (e.getCode() == 409L) {
                log.debug("更新打印机状态时未获取到分布式锁: printerId={}", printerId);
            } else {
                log.error("更新打印机状态到数据库失败: printerId={}", printerId, e);
            }
            return false;
        } catch (Exception e) {
            log.error("更新打印机状态到数据库失败: printerId={}", printerId, e);
            return false;
        }
    }

    @Override
    public boolean tryLockPrinterStatus(Long printerId) {
        String lockKey = CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_LOCK, printerId);
        String lockValue = UUID.randomUUID().toString();
        return cacheStore.tryLock(lockKey, lockValue, LOCK_TTL, TimeUnit.SECONDS);
    }

    @Override
    public void unlockPrinterStatus(Long printerId) {
        String lockKey = CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_LOCK, printerId);
        cacheStore.unlock(lockKey);
    }

    @Override
    public void recordStatusHistory(Long printerId, MoonrakerStatusDTO status) {
        if (printerId == null || status == null) return;
        
        try {
            String key = CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_HISTORY, printerId);
            cacheStore.listLeftPush(key, status);
            cacheStore.listTrim(key, 0, HISTORY_KEEP_COUNT - 1);
            cacheStore.expire(key, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("写入打印机状态历史失败: printerId={}", printerId, e);
        }

        // 进程内缓存保留高频短期数据；持久化历史只记录状态、文件或错误信息变化，
        // 避免打印过程中每分钟重复写入同一状态，保持“状态历史”的可读性。
        PersistedStatus current = PersistedStatus.from(status);
        PersistedStatus previous = lastPersistedStatuses.get(printerId);
        long now = System.currentTimeMillis();
        if (previous == null || !current.sameState(previous)) {
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
            String key = CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_HISTORY, printerId);
            return cacheStore.listRange(key, 0, count - 1, MoonrakerStatusDTO.class);
        } catch (Exception e) {
            log.error("读取打印机状态历史失败: printerId={}, count={}", printerId, count, e);
            return new ArrayList<>();
        }
    }

    @Override
    public long getOnlinePrinterCount() {
        try {
            cleanupExpiredHeartbeats();
            Long count = cacheStore.zSetSize(CacheKeyConstant.PRINTER_HEARTBEAT_ZSET);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("获取在线打印机数量失败", e);
            return 0;
        }
    }

    @Override
    public long getBusyPrinterCount() {
        try {
            Long count = cacheStore.setSize(CacheKeyConstant.PRINTER_BUSY_SET);
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
            cacheStore.zSetAdd(CacheKeyConstant.PRINTER_HEARTBEAT_ZSET,
                    printerId.toString(), System.currentTimeMillis());
        } catch (Exception e) {
            log.error("标记打印机在线失败: printerId={}", printerId, e);
        }
    }

    @Override
    public void markPrinterOffline(Long printerId) {
        if (printerId == null) return;
        
        try {
            cacheStore.zSetRemove(CacheKeyConstant.PRINTER_HEARTBEAT_ZSET, printerId.toString());
            cacheStore.setRemove(CacheKeyConstant.PRINTER_BUSY_SET, printerId.toString());
        } catch (Exception e) {
            log.error("标记打印机离线失败: printerId={}", printerId, e);
        }
    }

    @Override
    public void markPrinterBusy(Long printerId) {
        if (printerId == null) return;
        
        try {
            cacheStore.setAdd(CacheKeyConstant.PRINTER_BUSY_SET, printerId.toString());
        } catch (Exception e) {
            log.error("标记打印机忙碌失败: printerId={}", printerId, e);
        }
    }

    @Override
    public void markPrinterIdle(Long printerId) {
        if (printerId == null) return;
        
        try {
            cacheStore.setRemove(CacheKeyConstant.PRINTER_BUSY_SET, printerId.toString());
        } catch (Exception e) {
            log.error("标记打印机空闲失败: printerId={}", printerId, e);
        }
    }

    private void cleanupExpiredHeartbeats() {
        try {
            long cutoffTime = System.currentTimeMillis() - (HEARTBEAT_TIMEOUT_SECONDS * 1000);
            cacheStore.zSetRemoveRangeByScore(CacheKeyConstant.PRINTER_HEARTBEAT_ZSET, 0, cutoffTime);
        } catch (Exception e) {
            log.error("清理过期打印机心跳失败", e);
        }
    }

    @Override
    public void cacheSystemStats(Object stats) {
        if (stats == null) return;
        
        String key = CacheKeyConstant.getKey(CacheKeyConstant.SYSTEM_STATS, "overview");
        cacheStore.set(key, stats, 60, TimeUnit.SECONDS);
    }

    @Override
    public Object getSystemStats() {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.SYSTEM_STATS, "overview");
        return cacheStore.get(key, Object.class);
    }

    @Override
    public List<Printer> getAllPrintersFromCache() {
        String key = CacheKeyConstant.PRINTER_LIST;
        return cacheStore.get(key, new TypeReference<List<Printer>>() {});
    }

    @Override
    public void cacheAllPrinters(List<Printer> printers) {
        if (printers == null || printers.isEmpty()) {
            log.warn("缓存打印机列表时跳过空列表");
            return;
        }
        
        String key = CacheKeyConstant.PRINTER_LIST;
        cacheStore.set(key, printers, 1, TimeUnit.HOURS);
        log.info("打印机列表缓存完成: {} 台", printers.size());
    }

    @Override
    public void refreshPrinterCache() {
        String key = CacheKeyConstant.PRINTER_LIST;
        cacheStore.delete(key);
        log.info("已刷新打印机列表缓存");
    }

    @Override
    public boolean updatePrinterHealthWithLock(Printer printer) {
        if (printer == null || printer.getId() == null) {
            return false;
        }
        Long printerId = printer.getId();
        try {
            return PrinterLock.execute(printerId, cacheStore, () -> {
                PrinterLock.ensureValid(printerId);
                boolean updated = printerMapper.updateCommunicationHealth(printer) > 0;
                if (updated) {
                    refreshPrinterCache();
                }
                return updated;
            });
        } catch (BusinessException e) {
            if (e.getCode() != 409L) {
                log.error("更新打印机通信健康失败: printerId={}", printerId, e);
            }
            return false;
        } catch (Exception e) {
            log.error("更新打印机通信健康失败: printerId={}", printerId, e);
            return false;
        }
    }

    private record PersistedStatus(String unifiedState, String rawState,
                                   String systemMessage, String filename,
                                   String taskSource, Long farmJobId,
                                   String taskMatchReason, long recordedAt) {

        private static PersistedStatus from(MoonrakerStatusDTO status) {
            return new PersistedStatus(status.getUnifiedState(), status.getState(),
                    status.getSystemMessage(), status.getFilename(), status.getTaskSource(),
                    status.getFarmJobId(), status.getTaskMatchReason(), 0);
        }

        private PersistedStatus withRecordedAt(long timestamp) {
            return new PersistedStatus(unifiedState, rawState, systemMessage, filename,
                    taskSource, farmJobId, taskMatchReason, timestamp);
        }

        private boolean sameState(PersistedStatus other) {
            return Objects.equals(unifiedState, other.unifiedState)
                    && Objects.equals(rawState, other.rawState)
                    && Objects.equals(systemMessage, other.systemMessage)
                    && Objects.equals(filename, other.filename)
                    && Objects.equals(taskSource, other.taskSource)
                    && Objects.equals(farmJobId, other.farmJobId)
                    && Objects.equals(taskMatchReason, other.taskMatchReason);
        }
    }
}
