package com.example.farm.service;

import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;

import java.util.List;

/**
 * 打印机缓存服务接口。
 */
public interface PrinterCacheService {

    /**
     * 缓存打印机实时状态。
     *
     * @param printerId 打印机 ID
     * @param status 状态数据
     */
    void cachePrinterStatus(Long printerId, MoonrakerStatusDTO status);

    /**
     * 查询单台打印机缓存状态。
     *
     * @param printerId 打印机 ID
     * @return 状态数据；未命中时返回 `null`
     */
    MoonrakerStatusDTO getCachedStatus(Long printerId);

    /**
     * 批量查询打印机缓存状态。
     *
     * @param printerIds 打印机 ID 列表
     * @return 状态列表
     */
    List<MoonrakerStatusDTO> getCachedStatusBatch(List<Long> printerIds);

    /**
     * 清理单台打印机状态缓存。
     *
     * @param printerId 打印机 ID
     */
    void clearStatusCache(Long printerId);

    /**
     * 在分布式锁保护下更新打印机状态。
     *
     * @param printer 打印机实体
     * @return `true` 表示更新成功
     */
    boolean updatePrinterStatusWithLock(Printer printer);

    /**
     * 尝试获取打印机状态更新锁。
     *
     * @param printerId 打印机 ID
     * @return `true` 表示获取锁成功
     */
    boolean tryLockPrinterStatus(Long printerId);

    /**
     * 释放打印机状态更新锁。
     *
     * @param printerId 打印机 ID
     */
    void unlockPrinterStatus(Long printerId);

    /**
     * 记录状态历史。
     *
     * @param printerId 打印机 ID
     * @param status 状态数据
     */
    void recordStatusHistory(Long printerId, MoonrakerStatusDTO status);

    /**
     * 查询状态历史。
     *
     * @param printerId 打印机 ID
     * @param count 返回条数
     * @return 状态历史列表
     */
    List<MoonrakerStatusDTO> getStatusHistory(Long printerId, int count);

    /**
     * 统计在线打印机数量。
     *
     * @return 在线数量
     */
    long getOnlinePrinterCount();

    /**
     * 统计忙碌打印机数量。
     *
     * @return 忙碌数量
     */
    long getBusyPrinterCount();

    /**
     * 标记打印机在线。
     *
     * @param printerId 打印机 ID
     */
    void markPrinterOnline(Long printerId);

    /**
     * 标记打印机离线。
     *
     * @param printerId 打印机 ID
     */
    void markPrinterOffline(Long printerId);

    /**
     * 标记打印机忙碌。
     *
     * @param printerId 打印机 ID
     */
    void markPrinterBusy(Long printerId);

    /**
     * 标记打印机空闲。
     *
     * @param printerId 打印机 ID
     */
    void markPrinterIdle(Long printerId);

    /**
     * 缓存系统统计数据。
     *
     * @param stats 统计对象
     */
    void cacheSystemStats(Object stats);

    /**
     * 查询系统统计数据。
     *
     * @return 统计对象
     */
    Object getSystemStats();

    /**
     * 查询缓存中的打印机列表。
     *
     * @return 打印机列表
     */
    List<Printer> getAllPrintersFromCache();

    /**
     * 缓存全量打印机列表。
     *
     * @param printers 打印机列表
     */
    void cacheAllPrinters(List<Printer> printers);

    /**
     * 刷新打印机列表缓存。
     */
    void refreshPrinterCache();
}