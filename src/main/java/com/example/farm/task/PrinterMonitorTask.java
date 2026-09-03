package com.example.farm.task;

import com.example.farm.common.utils.LogUtil;
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
import com.example.farm.service.WebSocketEventPublisher;
import jakarta.annotation.PreDestroy;
import com.example.farm.config.PrinterMonitorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * 打印机监控任务
 * 定期获取打印机状态并推送至前端
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "farm.monitor", name = "enabled", havingValue = "true", matchIfMissing = false)
public class PrinterMonitorTask {

    private final PrinterService printerService;
    private final PrinterCacheService printerCacheService;
    private final PrinterProtocolAdapterFactory adapterFactory;
    private final PrintJobService printJobService;
    private final WebSocketEventPublisher eventPublisher;
    private final PrinterMonitorProperties monitorProperties;

    // 并发线程池，用于并行查询多个打印机状态
    private final ExecutorService executorService;
    private final AtomicBoolean scanInProgress = new AtomicBoolean();
    private final Map<Long, Boolean> lastOnlineStates = new ConcurrentHashMap<>();
    private final Map<Long, PrinterDeviceStatus> lastPublishedStatuses = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastOfflineLogAt = new ConcurrentHashMap<>();

    // 慢查询阈值：5秒
    private static final long SLOW_THRESHOLD_MS = 5000;
    private static final long OFFLINE_LOG_INTERVAL_MS = 60_000;

    @Autowired
    public PrinterMonitorTask(PrinterService printerService,
                              PrinterCacheService printerCacheService,
                              PrinterProtocolAdapterFactory adapterFactory,
                              PrintJobService printJobService,
                              WebSocketEventPublisher eventPublisher,
                              PrinterMonitorProperties monitorProperties) {
        this.printerService = printerService;
        this.printerCacheService = printerCacheService;
        this.adapterFactory = adapterFactory;
        this.printJobService = printJobService;
        this.eventPublisher = eventPublisher;
        this.monitorProperties = monitorProperties;
        int concurrency = Math.max(1, monitorProperties.getConcurrency());
        this.executorService = Executors.newFixedThreadPool(concurrency);
    }

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
    @Scheduled(fixedRateString = "${farm.monitor.interval:5s}")
    public void checkPrinterStatus() {
        if (!scanInProgress.compareAndSet(false, true)) {
            log.debug("跳过重叠的打印机状态巡检");
            return;
        }
        long startTime = System.currentTimeMillis();
        
        try {
            List<Printer> printers = loadMonitorPrinters();

            if (printers.isEmpty()) {
                scanInProgress.set(false);
                return;
            }

            // 并行查询打印机状态
            List<CompletableFuture<PrinterStatusResult>> futures = printers.stream()
                            .map(printer -> CompletableFuture.supplyAsync(
                                    () -> fetchAndUpdateStatus(printer), executorService)
                            .thenApply(result -> {
                                publishStatusEvent(result);
                                return result;
                            }))
                    .toList();

            // 异步统计
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> logScanResult(futures, startTime))
                    .whenComplete((ignored, error) -> scanInProgress.set(false));

        } catch (Exception e) {
            scanInProgress.set(false);
            log.error("打印机状态巡检失败", e);
        }
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
        // 白名单只决定巡检范围；真正执行设备动作前再次读取数据库，避免使用旧的任务绑定。
        Printer latestPrinter = printerService.getById(printerId);
        if (latestPrinter == null) {
            log.warn("监控设备已不存在，跳过状态同步: printerId={}", printerId);
            return new PrinterStatusResult(printerId, null, false, "打印机不存在");
        }
        printer = latestPrinter;
        String printerName = printer.getName();
        long startTime = System.currentTimeMillis();

        try {
            PrinterDeviceStatus status = adapterFactory.getAdapter(printer.getFirmwareType())
                    .getStatus(endpointOf(printer));
            long duration = System.currentTimeMillis() - startTime;

            if (status != null) {
                handlePrinterOnline(printerId, printerName, status, printer, duration);
                return new PrinterStatusResult(printerId, status, true, null);
            } else {
                handlePrinterOffline(printer);
                return new PrinterStatusResult(printerId, null, false, "设备未返回状态");
            }
        } catch (Exception e) {
            logOfflineQueryFailure(printerId, printerName, e);
            handlePrinterOffline(printer);
            return new PrinterStatusResult(printerId, null, false, "设备状态查询失败");
        }
    }

    /**
     * 只加载配置白名单中的设备，避免 v2 在没有真实设备时扫描历史设备。
     */
    private List<Printer> loadMonitorPrinters() {
        List<Long> printerIds = monitorProperties.getPrinterIds();
        if (printerIds == null || printerIds.isEmpty()) {
            log.debug("打印机监控白名单为空，跳过本轮巡检");
            return List.of();
        }

        List<Printer> printers = printerService.listByIds(printerIds);
        if (printers == null || printers.isEmpty()) {
            return List.of();
        }

        Map<Long, Printer> byId = printers.stream()
                .filter(printer -> printer != null && printer.getId() != null)
                .collect(java.util.stream.Collectors.toMap(Printer::getId, printer -> printer, (left, right) -> left));
        return printerIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private void handlePrinterOnline(Long printerId, String printerName,
                                     PrinterDeviceStatus status, Printer printer, long duration) {
        lastOfflineLogAt.remove(printerId);
        MoonrakerStatusDTO legacyStatus = toLegacyStatus(status);
        // 缓存状态
        printerCacheService.cachePrinterStatus(printerId, legacyStatus);
        printerCacheService.recordStatusHistory(printerId, legacyStatus);
        printerCacheService.markPrinterOnline(printerId);

        // 使用适配器输出的原始任务状态驱动现有农场业务逻辑。
        String deviceState = normalizeRawState(status.rawState());

        // ========== 核心业务逻辑：农场任务 vs 野生任务 ==========
        boolean requiresReconciliation = false;
        if ("printing".equals(deviceState)) {
            if (printer.getCurrentJobId() != null) {
                // 农场任务：同步打印进度
                requiresReconciliation = syncPrintJobStatus(printer, status);
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
            requiresReconciliation = syncPrintJobStatus(printer, status);
        }

        // 检查状态变更（保留原有逻辑，更新数据库状态）
        String newDbStatus = requiresReconciliation ? "ERROR" : determineDbStatus(status);
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
    private boolean syncPrintJobStatus(Printer printer, PrinterDeviceStatus status) {
        Long jobId = printer.getCurrentJobId();
        if (jobId == null) {
            return false;
        }

        PrintJob job = printJobService.getById(jobId);
        if (job == null) {
            log.warn("同步任务状态失败：任务不存在，jobId={}", jobId);
            return false;
        }

        String state = normalizeRawState(status.rawState());
        BigDecimal progress = status.progress();
        boolean jobChanged = false;
        boolean requiresReconciliation = false;

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

                // 先成功解绑机器，再结束任务；失败时保留绑定，等待下一轮巡检重试。
                if (!unbindPrinter(printer)) {
                    jobChanged = false;
                    break;
                }
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

                if (!unbindPrinter(printer)) {
                    jobChanged = false;
                    break;
                }
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

                if (!unbindPrinter(printer)) {
                    jobChanged = false;
                    break;
                }
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

            case "standby", "ready", "idle":
                // 设备空闲不等于任务完成。对仍处于执行态的 Farm 任务进入人工核对，
                // 保留绑定以阻止误派单，等待用户重新查询后决定完成、取消或重试。
                String normalizedJobStatus = PrintJobStatus.normalize(job.getStatus());
                if (PrintJobStatus.PRINTING.name().equals(normalizedJobStatus)
                        || PrintJobStatus.PAUSED.name().equals(normalizedJobStatus)
                        || PrintJobStatus.UPLOADING.name().equals(normalizedJobStatus)) {
                    if (transitionFromDevice(job, PrintJobStatus.RECONCILING)) {
                        job.setStatus(PrintJobStatus.RECONCILING.name());
                        job.setErrorReason("设备已返回空闲，无法确认任务终态，请人工核对");
                        jobChanged = true;
                        requiresReconciliation = true;
                    }
                }
                break;

            default:
                break;
        }

        // 保存任务变更
        if (jobChanged) {
            if (printJobService.updateById(job)) {
                eventPublisher.publishJobStatus(job);
            }
        }
        return requiresReconciliation;
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

    private boolean unbindPrinter(Printer printer) {
        Long jobId = printer.getCurrentJobId();
        if (jobId == null || !printerService.clearJobBinding(printer.getId(), jobId)) {
            log.error("设备终态同步失败：打印机解绑保存失败，printerId={}, jobId={}",
                    printer.getId(), printer.getCurrentJobId());
            return false;
        }
        printer.setCurrentJobId(null);
        printer.setIsSafeToPrint(false);
        return true;
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

    private void logOfflineQueryFailure(Long printerId, String printerName, Exception exception) {
        long now = System.currentTimeMillis();
        AtomicBoolean shouldLog = new AtomicBoolean(false);
        lastOfflineLogAt.compute(printerId, (ignored, previous) -> {
            if (previous == null || now - previous >= OFFLINE_LOG_INTERVAL_MS) {
                shouldLog.set(true);
                return now;
            }
            return previous;
        });

        if (shouldLog.get()) {
            log.warn("获取打印机状态失败（离线日志已限频）: printerId={}, name={}",
                    printerId, printerName, exception);
        } else {
            log.debug("打印机仍处于离线状态: printerId={}, name={}", printerId, printerName);
        }
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

    private void publishStatusEvent(PrinterStatusResult result) {
        Boolean wasOnline = lastOnlineStates.put(result.printerId, result.online);
        if (!result.online) {
            lastPublishedStatuses.remove(result.printerId);
            if (!Boolean.FALSE.equals(wasOnline)) {
                eventPublisher.publishPrinterOffline(result.printerId, result.offlineReason);
            }
            return;
        }

        PrinterDeviceStatus previous = lastPublishedStatuses.put(result.printerId, result.status);
        if (!result.status.equals(previous)) {
            eventPublisher.publishPrinterStatus(result.printerId, result.status);
        }
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

    private record PrinterStatusResult(Long printerId, PrinterDeviceStatus status,
                                       boolean online, String offlineReason) {
    }
}
