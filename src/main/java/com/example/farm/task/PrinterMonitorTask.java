package com.example.farm.task;

import com.example.farm.common.utils.LogUtil;
import com.example.farm.controller.WebSocketServer;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrintJobService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 打印机监控任务
 * 定期获取打印机状态并推送至前端
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "farm.tasks", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PrinterMonitorTask {

    private final PrinterService printerService;
    private final PrinterCacheService printerCacheService;
    private final PrinterProtocolAdapterFactory adapterFactory;
    private final PrintJobService printJobService;

    // 并发线程池，用于并行查询多个打印机状态
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    // 慢查询阈值：5秒
    private static final long SLOW_THRESHOLD_MS = 5000;

    @PreDestroy
    public void destroy() {
        LogUtil.shutdown("PrinterMonitorTask");
        // 优雅关闭线程池：先停止接受新任务，然后等待现有任务完成
        executorService.shutdown();
        try {
            // 等待最多 5 秒让正在执行的任务完成
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                // 如果超时，强制关闭
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            // 如果被中断，强制关闭
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 每5秒执行一次状态监控
     */
    @Scheduled(fixedRate = 5000)
    public void checkPrinterStatus() {
        long startTime = System.currentTimeMillis();
        
        try {
            // 优先从Redis获取打印机列表
            List<Printer> printers = printerCacheService.getAllPrintersFromCache();
            if (printers == null) {
                printers = loadPrintersFromDb();
                if (printers.isEmpty()) {
                    return;
                }
            }

            if (printers.isEmpty()) {
                return;
            }

            // 并行查询打印机状态
            List<CompletableFuture<PrinterStatusResult>> futures = printers.stream()
                    .map(printer -> CompletableFuture.supplyAsync(
                                    () -> fetchAndUpdateStatus(printer), executorService)
                            .thenApply(result -> {
                                if (result.status != null) {
                                    pushToFrontend(result.printerId, result.status);
                                }
                                return result;
                            }))
                    .toList();

            // 异步统计
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> logScanResult(futures, startTime));
                    
        } catch (Exception e) {
            log.error("打印机状态巡检失败", e);
        }
    }

    private List<Printer> loadPrintersFromDb() {
        long startTime = System.currentTimeMillis();
        List<Printer> printers = printerService.list();
        long duration = System.currentTimeMillis() - startTime;
        
        LogUtil.slowOperation("loadPrintersFromDb", duration, 1000);
        
        if (!printers.isEmpty()) {
            printerCacheService.cacheAllPrinters(printers);
            log.info("已从数据库加载打印机列表到缓存: {} 台", printers.size());
        }
        return printers;
    }

    private void logScanResult(List<CompletableFuture<PrinterStatusResult>> futures, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        long onlineCount = futures.stream().map(CompletableFuture::join).filter(r -> r.online).count();
        long offlineCount = futures.size() - onlineCount;
        
        if (duration > SLOW_THRESHOLD_MS) {
            log.warn("打印机巡检较慢: 耗时 {}ms，在线 {} 台，离线 {} 台", 
                    duration, onlineCount, offlineCount);
        } else if (log.isDebugEnabled()) {
            log.debug("打印机巡检完成: 耗时 {}ms，在线 {} 台，离线 {} 台", 
                    duration, onlineCount, offlineCount);
        }
    }

    private PrinterStatusResult fetchAndUpdateStatus(Printer printer) {
        Long printerId = printer.getId();
        String printerName = printer.getName();
        long startTime = System.currentTimeMillis();

        try {
            PrinterDeviceStatus status = adapterFactory.getAdapter(printer.getFirmwareType())
                    .getStatus(endpointOf(printer));
            long duration = System.currentTimeMillis() - startTime;

            if (status != null) {
                handlePrinterOnline(printerId, printerName, status, printer, duration);
                return new PrinterStatusResult(printerId, status, true);
            } else {
                handlePrinterOffline(printer);
                return new PrinterStatusResult(printerId, null, false);
            }
        } catch (Exception e) {
            log.error("获取打印机状态失败: printerId={}, name={}",
                    printerId, printerName, e);
            handlePrinterOffline(printer);
            return new PrinterStatusResult(printerId, null, false);
        }
    }

    private void handlePrinterOnline(Long printerId, String printerName,
                                     PrinterDeviceStatus status, Printer printer, long duration) {
        MoonrakerStatusDTO legacyStatus = toLegacyStatus(status);
        // 缓存状态
        printerCacheService.cachePrinterStatus(printerId, legacyStatus);
        printerCacheService.recordStatusHistory(printerId, legacyStatus);
        printerCacheService.markPrinterOnline(printerId);

        // 使用适配器输出的原始任务状态驱动现有农场业务逻辑。
        String deviceState = normalizeRawState(status.rawState());

        // ========== 核心业务逻辑：农场任务 vs 野生任务 ==========
        if ("printing".equals(deviceState)) {
            if (printer.getCurrentJobId() != null) {
                // 农场任务：同步打印进度
                syncPrintJobStatus(printer, status);
            } else {
                // 野生任务：锁定机器，防止被自动调度抢单
                if (!"PRINTING".equals(printer.getStatus())) {
                    updatePrinterStatus(printer, "PRINTING");
                    log.info("发现单机直连打印任务，锁定机器: {}", printer.getName());
                }
            }
        } else if (("complete".equals(deviceState) || "standby".equals(deviceState) || "ready".equals(deviceState))
                && printer.getCurrentJobId() == null && "PRINTING".equals(printer.getStatus())) {
            // 野生任务结束：释放机器
            updatePrinterStatus(printer, "IDLE");
            log.info("单机直连打印任务结束，释放机器: {}", printer.getName());
        } else if (printer.getCurrentJobId() != null) {
            // 农场任务：其他状态同步（complete/error/cancelled/paused）
            syncPrintJobStatus(printer, status);
        }

        // 检查状态变更（保留原有逻辑，更新数据库状态）
        String newDbStatus = determineDbStatus(status);
        if (!newDbStatus.equals(printer.getStatus())) {
            updatePrinterStatus(printer, newDbStatus);
        }

        // 记录慢查询
        LogUtil.slowOperation("fetchPrinterStatus", duration, SLOW_THRESHOLD_MS);
    }

    /**
     * 同步打印任务状态（农场任务专用）
     * 根据 Moonraker 状态更新 PrintJob 表，并在任务结束时解绑机器
     */
    private void syncPrintJobStatus(Printer printer, PrinterDeviceStatus status) {
        Long jobId = printer.getCurrentJobId();
        if (jobId == null) {
            return;
        }

        PrintJob job = printJobService.getById(jobId);
        if (job == null) {
            log.warn("同步任务状态失败：任务不存在，jobId={}", jobId);
            return;
        }

        String state = normalizeRawState(status.rawState());
        BigDecimal progress = status.progress();
        boolean jobChanged = false;

        switch (state) {
            case "printing":
                // 更新进度（差值大于 1.0% 时才更新）
                if (progress != null) {
                    BigDecimal newProgress = progress;
                    BigDecimal oldProgress = job.getProgress() != null ? job.getProgress() : BigDecimal.ZERO;
                    if (newProgress.subtract(oldProgress).doubleValue() > 1.0) {
                        job.setProgress(newProgress);
                        jobChanged = true;
                    }
                }
                break;

            case "complete":
                // 任务完成
                if (!transitionFromDevice(job, PrintJobStatus.COMPLETED)) {
                    break;
                }
                job.setStatus(PrintJobStatus.COMPLETED.name());
                job.setProgress(BigDecimal.valueOf(100));
                job.setCompletedAt(LocalDateTime.now());
                jobChanged = true;

                // 解绑机器
                printer.setCurrentJobId(null);
                printer.setIsSafeToPrint(false);
                printerService.updateById(printer);
                log.info("打印任务完成，已解绑机器: jobId={}, printerId={}", jobId, printer.getId());
                break;

            case "error":
                // 任务失败
                if (!transitionFromDevice(job, PrintJobStatus.FAILED)) {
                    break;
                }
                job.setStatus(PrintJobStatus.FAILED.name());
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorReason("打印出错");
                jobChanged = true;

                // 解绑机器
                printer.setCurrentJobId(null);
                printerService.updateById(printer);
                log.info("打印任务失败，已解绑机器: jobId={}, printerId={}", jobId, printer.getId());
                break;

            case "cancelled":
                // 任务取消
                if (!transitionFromDevice(job, PrintJobStatus.CANCELLED)) {
                    break;
                }
                job.setStatus(PrintJobStatus.CANCELLED.name());
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorReason("用户取消");
                jobChanged = true;

                // 解绑机器
                printer.setCurrentJobId(null);
                printerService.updateById(printer);
                log.info("打印任务失败/取消，已解绑机器: jobId={}, printerId={}, state={}", jobId, printer.getId(), state);
                break;

            case "paused":
                // 暂停（需 PrintJob 支持 PAUSED 状态）
                if (!PrintJobStatus.PAUSED.name().equals(PrintJobStatus.normalize(job.getStatus()))) {
                    if (!transitionFromDevice(job, PrintJobStatus.PAUSED)) {
                        break;
                    }
                    job.setStatus(PrintJobStatus.PAUSED.name());
                    jobChanged = true;
                }
                break;

            default:
                break;
        }

        // 保存任务变更
        if (jobChanged) {
            printJobService.updateById(job);
        }
    }

    private boolean transitionFromDevice(PrintJob job, PrintJobStatus targetStatus) {
        if (targetStatus.name().equals(PrintJobStatus.normalize(job.getStatus()))) {
            return false;
        }
        try {
            PrintJobStatus.requireTransition(job.getStatus(), targetStatus);
            return true;
        } catch (com.example.farm.common.exception.BusinessException e) {
            log.warn("设备状态无法驱动任务流转: jobId={}, currentStatus={}, targetStatus={}",
                    job.getId(), job.getStatus(), targetStatus);
            return false;
        }
    }

    private void updatePrinterStatus(Printer printer, String newStatus) {
        Printer updateEntity = new Printer();
        updateEntity.setId(printer.getId());
        updateEntity.setStatus(newStatus);

        boolean updated = printerCacheService.updatePrinterStatusWithLock(updateEntity);
        if (updated) {
            LogUtil.dataChange("STATUS_CHANGE", "FarmPrinter", printer.getId(),
                    printer.getStatus() + " -> " + newStatus);
            printer.setStatus(newStatus);

            // 更新忙碌状态
            if ("PRINTING".equals(newStatus)) {
                printerCacheService.markPrinterBusy(printer.getId());
            } else if ("IDLE".equals(newStatus)) {
                printerCacheService.markPrinterIdle(printer.getId());
            }
        }
    }

    private void handlePrinterOffline(Printer printer) {
        if (!"OFFLINE".equals(printer.getStatus())) {
            Printer updateEntity = new Printer();
            updateEntity.setId(printer.getId());
            updateEntity.setStatus("OFFLINE");

            boolean updated = printerCacheService.updatePrinterStatusWithLock(updateEntity);
            if (updated) {
                LogUtil.dataChange("STATUS_CHANGE", "FarmPrinter", printer.getId(), "-> OFFLINE");
                printer.setStatus("OFFLINE");
            }
        }
        printerCacheService.markPrinterOffline(printer.getId());
        printerCacheService.clearStatusCache(printer.getId());
    }

    /**
     * 根据统一状态确定数据库状态
     * 注意：此处的 moonrakerState 已经是经过 calculateUnifiedState 处理后的统一状态
     * 可能包含系统级状态：shutdown, startup, error, ready 以及任务级状态：printing, paused 等
     */
    private String determineDbStatus(PrinterDeviceStatus status) {
        if (status == null || status.status() == null) return "OFFLINE";

        String rawState = normalizeRawState(status.rawState());
        return switch (rawState) {
            // 任务级状态
            case "printing", "paused" -> "PRINTING";
            case "standby", "complete" -> "IDLE";
            // 系统级状态 - 需要特殊处理
            case "shutdown" -> "ERROR";  // 热失控等安全保护触发
            case "startup" -> "OFFLINE"; // 启动中视为离线
            case "error" -> "ERROR";     // 系统错误
            case "ready" -> "IDLE";      // 就绪但无任务
            case "offline", "unknown" -> "OFFLINE";
            default -> switch (status.status()) {
                case OFFLINE, UNKNOWN -> "OFFLINE";
                case PREPARING -> "PREPARING";
                case PRINTING, PAUSED -> "PRINTING";
                case ERROR -> "ERROR";
                case IDLE -> "IDLE";
            };
        };
    }

    private void pushToFrontend(Long printerId, PrinterDeviceStatus status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("printerId", printerId);
        payload.put("data", status);
        payload.put("timestamp", System.currentTimeMillis());
        WebSocketServer.broadcastPrinterStatus(payload);
    }

    private MoonrakerStatusDTO toLegacyStatus(PrinterDeviceStatus status) {
        MoonrakerStatusDTO legacy = new MoonrakerStatusDTO();
        legacy.setState(status.rawState());
        legacy.setSystemMessage(status.systemMessage());
        legacy.setFilename(status.filename());
        legacy.setProgress(status.progress() == null ? null : status.progress().doubleValue());
        legacy.setToolTemperature(doubleValue(status.toolTemperature()));
        legacy.setToolTarget(doubleValue(status.toolTarget()));
        legacy.setBedTemperature(doubleValue(status.bedTemperature()));
        legacy.setBedTarget(doubleValue(status.bedTarget()));
        legacy.setPrintDuration(doubleValue(status.printDuration()));
        legacy.setTotalDuration(doubleValue(status.totalDuration()));
        legacy.setFilamentUsed(doubleValue(status.filamentUsed()));
        legacy.setUnifiedState(status.status() == null ? PrinterStatus.UNKNOWN.name() : status.status().name());
        return legacy;
    }

    private Double doubleValue(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private PrinterEndpoint endpointOf(Printer printer) {
        PrinterProtocolType protocolType = PrinterProtocolType.normalize(printer.getFirmwareType());
        if (printer.getIpAddress() == null || printer.getIpAddress().isBlank()) {
            throw new IllegalArgumentException("打印机没有可用的网络地址");
        }
        return new PrinterEndpoint(printer.getId(), printer.getIpAddress(), printer.getApiKey(), protocolType);
    }

    private String normalizeRawState(String rawState) {
        return rawState == null || rawState.isBlank()
                ? "unknown"
                : rawState.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record PrinterStatusResult(Long printerId, PrinterDeviceStatus status, boolean online) {
    }
}
