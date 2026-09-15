package com.example.farm.task;

import com.example.farm.common.utils.LogUtil;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.enums.DeviceTaskSource;
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
import com.example.farm.mapper.PrintFileMapper;
import jakarta.annotation.PreDestroy;
import com.example.farm.config.PrinterMonitorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Duration;
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

    /** 旧版单元测试可不提供文件 Mapper；生产环境用于校验设备当前文件与 Farm 任务一致。 */
    @Autowired(required = false)
    private PrintFileMapper printFileMapper;

    @Autowired(required = false)
    private RedisUtil redisUtil;

    // 并发线程池，用于并行查询多个打印机状态
    private final ExecutorService executorService;
    private final AtomicBoolean scanInProgress = new AtomicBoolean();
    private final Map<Long, Boolean> lastOnlineStates = new ConcurrentHashMap<>();
    private final Map<Long, String> lastPublishedConnectionStatuses = new ConcurrentHashMap<>();
    private final Map<Long, PrinterDeviceStatus> lastPublishedStatuses = new ConcurrentHashMap<>();
    private final Map<Long, TaskObservation> lastPublishedTaskObservations = new ConcurrentHashMap<>();
    private final Map<Long, TaskObservation> lastLoggedTaskObservations = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastOfflineLogAt = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastHealthPersistAt = new ConcurrentHashMap<>();
    private final Map<Long, Integer> consecutiveSuccessCounts = new ConcurrentHashMap<>();
    private final Map<Long, Long> nextPollAt = new ConcurrentHashMap<>();

    // 慢查询阈值：5秒
    private static final long SLOW_THRESHOLD_MS = 5000;
    private static final long OFFLINE_LOG_INTERVAL_MS = 60_000;
    private static final long HEALTH_PERSIST_INTERVAL_MS = 15_000;

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
        long skippedCount = futures.stream().map(CompletableFuture::join).filter(PrinterStatusResult::skipped).count();
        long offlineCount = futures.size() - onlineCount - skippedCount;
        
        if (duration > SLOW_THRESHOLD_MS) {
            log.warn("打印机巡检较慢: 耗时 {}ms，在线 {} 台，离线 {} 台，跳过 {} 台",
                    duration, onlineCount, offlineCount, skippedCount);
        } else if (log.isDebugEnabled()) {
            log.debug("打印机巡检完成: 耗时 {}ms，在线 {} 台，离线 {} 台，跳过 {} 台",
                    duration, onlineCount, offlineCount, skippedCount);
        }
    }

    private PrinterStatusResult fetchAndUpdateStatus(Printer printer) {
        AtomicBoolean actionStarted = new AtomicBoolean();
        try {
            return PrinterLock.execute(printer.getId(), redisUtil, () -> {
                PrinterLock.ensureValid(printer.getId());
                actionStarted.set(true);
                PrinterStatusResult fetched = doFetchAndUpdateStatus(printer, null, null);
                PrinterLock.ensureValid(printer.getId());
                scheduleNextPoll(fetched);
                return fetched;
            });
        } catch (BusinessException exception) {
            if (!actionStarted.get() && exception.getCode() == 409L) {
                return new PrinterStatusResult(printer.getId(), null, false,
                        "其他监控实例正在巡检", null, null, null, null, null, true, null);
            }
            throw exception;
        }
    }

    private PrinterStatusResult doFetchAndUpdateStatus(Printer printer, String ignoredLockKey, String ignoredLockValue) {
        Long printerId = printer.getId();
        // 目标列表只决定巡检范围；真正执行设备动作前再次读取数据库，避免使用旧的任务绑定。
        Printer latestPrinter = printerService.getById(printerId);
        if (latestPrinter == null) {
            log.warn("监控设备已不存在，跳过状态同步: printerId={}", printerId);
            return new PrinterStatusResult(printerId, null, false, "打印机不存在",
                    null, null, null, null, null, false, null);
        }
        printer = latestPrinter;
        String printerName = printer.getName();
        long startTime = System.currentTimeMillis();

        try {
            PrinterDeviceStatus status = adapterFactory.getAdapter(printer.getFirmwareType())
                    .getStatus(endpointOf(printer));
            long duration = System.currentTimeMillis() - startTime;

            if (status != null) {
                TaskObservation taskObservation = handlePrinterOnline(
                        printerId, printerName, status, printer, duration);
                return new PrinterStatusResult(printerId, status, true, null,
                        printer.getConnectionStatus(), printer.getLastKnownStatus(),
                        printer.getLastSeenAt(), printer.getLastCommunicationError(),
                        printer.getConsecutiveFailureCount(), false, taskObservation);
            } else {
                handleCommunicationFailure(printer, "设备未返回状态");
                return new PrinterStatusResult(printerId, null, false, "设备未返回状态",
                        printer.getConnectionStatus(), printer.getLastKnownStatus(),
                        printer.getLastSeenAt(), printer.getLastCommunicationError(),
                        printer.getConsecutiveFailureCount(), false, null);
            }
        } catch (Exception e) {
            logOfflineQueryFailure(printerId, printerName, e);
            handleCommunicationFailure(printer, summarizeException(e));
            return new PrinterStatusResult(printerId, null, false, "设备状态查询失败",
                    printer.getConnectionStatus(), printer.getLastKnownStatus(),
                    printer.getLastSeenAt(), printer.getLastCommunicationError(),
                    printer.getConsecutiveFailureCount(), false, null);
        }
    }

    /**
     * 默认按数据库监控开关选择设备；保留非空 printer-ids 作为旧配置的临时兼容限制。
     */
    private List<Printer> loadMonitorPrinters() {
        List<Long> printerIds = monitorProperties.getPrinterIds();
        List<Printer> printers;
        if (printerIds != null && !printerIds.isEmpty()) {
            log.warn("farm.monitor.printer-ids 已废弃，当前仅作为临时限制；请迁移到打印机监控开关");
            printers = printerService.listByIds(printerIds);
        } else {
            printers = printerService.listMonitorPrinters();
        }
        if (printers == null || printers.isEmpty()) {
            return List.of();
        }

        if (printerIds == null || printerIds.isEmpty()) {
            return printers.stream()
                    .filter(printer -> printer != null && printer.getId() != null)
                    .filter(this::isDueForPolling)
                    .toList();
        }
        Map<Long, Printer> byId = printers.stream()
                .filter(printer -> printer != null && printer.getId() != null)
                .collect(java.util.stream.Collectors.toMap(Printer::getId, printer -> printer, (left, right) -> left));
        return printerIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .filter(this::isDueForPolling).toList();
    }

    private boolean isDueForPolling(Printer printer) {
        // 未迁移的旧对象没有健康字段，不应用退避，避免兼容调用被意外跳过。
        if (printer.getConnectionStatus() == null && printer.getConsecutiveFailureCount() == null) {
            return true;
        }
        return System.currentTimeMillis() >= nextPollAt.getOrDefault(printer.getId(), 0L);
    }

    private void scheduleNextPoll(PrinterStatusResult result) {
        if (result == null || result.printerId == null || result.skipped
                || result.connectionStatus == null) {
            return;
        }
        Duration delay;
        if ("OFFLINE".equalsIgnoreCase(result.connectionStatus)) {
            delay = monitorProperties.getOfflineInterval();
        } else if (result.status != null && isIdleState(result.status.rawState())) {
            delay = monitorProperties.getIdleInterval();
        } else {
            delay = monitorProperties.getInterval();
        }
        long delayMillis = delay == null ? 5_000L : Math.max(1_000L, delay.toMillis());
        nextPollAt.put(result.printerId, System.currentTimeMillis() + delayMillis);
    }

    private boolean isIdleState(String rawState) {
        String state = normalizeRawState(rawState);
        return "idle".equals(state) || "standby".equals(state)
                || "ready".equals(state) || "complete".equals(state);
    }

    private TaskObservation handlePrinterOnline(Long printerId, String printerName,
                                                PrinterDeviceStatus status, Printer printer, long duration) {
        lastOfflineLogAt.remove(printerId);
        recordCommunicationSuccess(printer, status);
        TaskObservationContext taskContext = classifyTaskObservation(printer, status);
        TaskObservation taskObservation = taskContext.observation();
        MoonrakerStatusDTO legacyStatus = toLegacyStatus(status, taskObservation);
        // 缓存状态
        printerCacheService.cachePrinterStatus(printerId, legacyStatus);
        printerCacheService.recordStatusHistory(printerId, legacyStatus);
        printerCacheService.markPrinterOnline(printerId);
        logTaskObservationChange(printer, taskObservation);

        // 使用适配器输出的原始任务状态驱动现有农场业务逻辑。
        String deviceState = normalizeRawState(status.rawState());

        // ========== 核心业务逻辑：农场任务 vs 野生任务 ==========
        boolean requiresReconciliation = false;
        if (isDeviceExecuting(deviceState)) {
            if (taskObservation.source() == DeviceTaskSource.FARM
                    && printer.getCurrentJobId() != null) {
                // 农场任务：同步打印进度
                requiresReconciliation = syncPrintJobStatus(printer, status, taskContext.farmJob());
            } else {
                // 外部任务或来源未知：按设备真实状态锁定机器，但不改动等待中的 Farm 任务。
                reconcileConflictingActiveFarmJob(printer, taskObservation, taskContext.farmJob());
                if (!"PRINTING".equals(printer.getStatus())) {
                    updatePrinterStatus(printer, "PRINTING");
                }
            }
        } else if (("complete".equals(deviceState) || "standby".equals(deviceState) || "ready".equals(deviceState))
                && printer.getCurrentJobId() == null && "PRINTING".equals(printer.getStatus())) {
            // 野生任务结束：释放机器
            updatePrinterStatus(printer, "IDLE");
            log.info("单机直连打印任务结束，释放机器: {}", printer.getName());
        } else if (printer.getCurrentJobId() != null) {
            // 农场任务：其他状态同步（complete/error/cancelled/paused）
            requiresReconciliation = syncPrintJobStatus(printer, status, taskContext.farmJob());
        }

        // 检查状态变更（保留原有逻辑，更新数据库状态）
        String newDbStatus = requiresReconciliation ? "ERROR" : determineDbStatus(status);
        if (!newDbStatus.equals(printer.getStatus())) {
            updatePrinterStatus(printer, newDbStatus);
        }

        // 记录慢查询
        LogUtil.slowOperation("fetchPrinterStatus", duration, SLOW_THRESHOLD_MS);
        return taskObservation;
    }

    /**
     * 同步打印任务状态（农场任务专用）
     * 根据 Moonraker 状态更新 PrintJob 表，并在任务结束时解绑机器
     */
    private boolean syncPrintJobStatus(Printer printer, PrinterDeviceStatus status, PrintJob job) {
        Long jobId = printer.getCurrentJobId();
        if (jobId == null) {
            return false;
        }

        if (job == null) {
            log.error("发现幽灵绑定：打印机绑定的任务不存在，保留绑定并标记设备 ERROR: printerId={}, jobId={}",
                    printer.getId(), jobId);
            return true;
        }
        if (!java.util.Objects.equals(job.getPrinterId(), printer.getId())) {
            log.error("发现幽灵绑定：任务反向绑定不一致，保留绑定并标记设备 ERROR: printerId={}, jobId={}, jobPrinterId={}",
                    printer.getId(), jobId, job.getPrinterId());
            return true;
        }

        String state = normalizeRawState(status.rawState());
        BigDecimal progress = status.progress();
        boolean jobChanged = false;
        boolean requiresReconciliation = false;

        switch (state) {
            case "printing":
                if (!PrintJobStatus.PRINTING.name().equals(PrintJobStatus.normalize(job.getStatus()))) {
                    if (!transitionFromDevice(job, PrintJobStatus.PRINTING)) {
                        break;
                    }
                    job.setStatus(PrintJobStatus.PRINTING.name());
                    if (job.getStartedAt() == null) {
                        job.setStartedAt(LocalDateTime.now());
                    }
                    jobChanged = true;
                }
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
                // RRF 的 idle/standby 可能表示尚未开始，也可能表示刚完成、取消或中止。
                // 只有设备提供了明确证据才结束 Farm 任务，否则保留绑定并进入人工核对。
                if (PrintJobStatus.READY.name().equals(PrintJobStatus.normalize(job.getStatus()))) {
                    // 仅上传成功后的预期状态：设备保持空闲，任务保持 READY，等待人工启动。
                    break;
                } else if (isRecentUploading(job)) {
                    // 文件传输和启动确认在数据库事务之外执行；给设备调用保留短暂窗口，
                    // 避免巡检把正常进行中的 UPLOADING 误判为异常。
                    break;
                } else if (Boolean.TRUE.equals(status.lastFileCancelled())) {
                    jobChanged = finishJobFromDevice(printer, job, PrintJobStatus.CANCELLED,
                            "设备报告最近一次文件已取消");
                } else if (Boolean.TRUE.equals(status.lastFileAborted())) {
                    jobChanged = finishJobFromDevice(printer, job, PrintJobStatus.FAILED,
                            "设备报告最近一次文件已中止");
                } else if (hasCompletionEvidence(status)) {
                    jobChanged = finishJobFromDevice(printer, job, PrintJobStatus.COMPLETED, null);
                } else {
                    requiresReconciliation = reconcileIdleJob(job);
                    jobChanged = requiresReconciliation;
                }
                break;

            case "halted":
                // 急停/故障后的设备状态不能自动视为完成；保留绑定，等待人工确认和后续处理。
                requiresReconciliation = reconcileIdleJob(job,
                        "设备处于 halted 状态，任务结果需要人工确认");
                jobChanged = requiresReconciliation;
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

    private boolean isDeviceExecuting(String state) {
        return "printing".equals(state) || "paused".equals(state);
    }

    private TaskObservationContext classifyTaskObservation(Printer printer, PrinterDeviceStatus status) {
        String state = normalizeRawState(status.rawState());
        Long farmJobId = printer.getCurrentJobId();
        PrintJob farmJob = farmJobId == null ? null : printJobService.getById(farmJobId);
        String farmJobStatus = farmJob == null ? null : PrintJobStatus.normalize(farmJob.getStatus());
        String observedFilename = fileBasename(status.filename());

        if (!isDeviceExecuting(state)) {
            return new TaskObservationContext(new TaskObservation(DeviceTaskSource.NONE, farmJobId, farmJobStatus,
                    "DEVICE_NOT_EXECUTING", observedFilename), farmJob);
        }
        if (farmJobId == null) {
            return new TaskObservationContext(new TaskObservation(DeviceTaskSource.EXTERNAL, null, null,
                    "NO_FARM_JOB", observedFilename), null);
        }
        if (farmJob == null) {
            return new TaskObservationContext(new TaskObservation(DeviceTaskSource.UNKNOWN, farmJobId, null,
                    "FARM_JOB_MISSING", observedFilename), null);
        }
        if (!java.util.Objects.equals(farmJob.getPrinterId(), printer.getId())) {
            return new TaskObservationContext(new TaskObservation(DeviceTaskSource.UNKNOWN, farmJobId, farmJobStatus,
                    "FARM_JOB_BINDING_MISMATCH", observedFilename), farmJob);
        }

        ObservedFileMatch fileMatch = compareObservedFile(farmJob, status);
        if (fileMatch == ObservedFileMatch.MATCHED
                && PrintJobStatus.ASSIGNED.name().equals(farmJobStatus)) {
            return new TaskObservationContext(new TaskObservation(DeviceTaskSource.EXTERNAL, farmJobId, farmJobStatus,
                    "FARM_JOB_NOT_TRANSFERRED", observedFilename), farmJob);
        }
        TaskObservation observation = switch (fileMatch) {
            case MATCHED -> new TaskObservation(DeviceTaskSource.FARM, farmJobId, farmJobStatus,
                    "FILENAME_MATCHED", observedFilename);
            case MISMATCHED -> new TaskObservation(DeviceTaskSource.EXTERNAL, farmJobId, farmJobStatus,
                    "FILENAME_MISMATCH", observedFilename);
            case UNKNOWN -> new TaskObservation(DeviceTaskSource.UNKNOWN, farmJobId, farmJobStatus,
                    "DEVICE_FILENAME_UNAVAILABLE", observedFilename);
        };
        return new TaskObservationContext(observation, farmJob);
    }

    private ObservedFileMatch compareObservedFile(PrintJob job, PrinterDeviceStatus status) {
        if (status.filename() == null || status.filename().isBlank() || printFileMapper == null) {
            return ObservedFileMatch.UNKNOWN;
        }
        PrintFile expected = printFileMapper.selectById(job.getFileId());
        if (expected == null) {
            return ObservedFileMatch.UNKNOWN;
        }
        String observed = fileBasename(status.filename());
        if (sameFilename(observed, fileBasename(expected.getOriginalName()))
                || sameFilename(observed, fileBasename(expected.getSafeName()))) {
            return ObservedFileMatch.MATCHED;
        }
        return ObservedFileMatch.MISMATCHED;
    }

    private void reconcileConflictingActiveFarmJob(Printer printer, TaskObservation observation, PrintJob job) {
        if (observation.source() != DeviceTaskSource.EXTERNAL
                || !"FILENAME_MISMATCH".equals(observation.matchReason())
                || observation.farmJobId() == null) {
            return;
        }
        if (job == null) {
            return;
        }
        String jobStatus = PrintJobStatus.normalize(job.getStatus());
        if (!PrintJobStatus.PRINTING.name().equals(jobStatus)
                && !PrintJobStatus.PAUSED.name().equals(jobStatus)) {
            return;
        }
        if (reconcileIdleJob(job,
                "设备正在打印其他文件，平台任务结果需要人工核对")) {
            if (printJobService.updateById(job)) {
                eventPublisher.publishJobStatus(job);
            }
        }
    }

    private void logTaskObservationChange(Printer printer, TaskObservation observation) {
        TaskObservation previous = lastLoggedTaskObservations.put(printer.getId(), observation);
        if (observation.equals(previous)) {
            return;
        }
        switch (observation.source()) {
            case EXTERNAL -> log.warn(
                    "检测到设备端外部打印: printerId={}, printerName={}, observedFile={}, farmJobId={}, reason={}",
                    printer.getId(), printer.getName(), observation.deviceFilename(),
                    observation.farmJobId(), observation.matchReason());
            case UNKNOWN -> log.warn(
                    "设备正在打印但无法确认任务来源: printerId={}, printerName={}, observedFile={}, farmJobId={}, reason={}",
                    printer.getId(), printer.getName(), observation.deviceFilename(),
                    observation.farmJobId(), observation.matchReason());
            case FARM -> log.info(
                    "设备打印已匹配平台任务: printerId={}, farmJobId={}, observedFile={}",
                    printer.getId(), observation.farmJobId(), observation.deviceFilename());
            case NONE -> {
                if (previous != null && (previous.source() == DeviceTaskSource.EXTERNAL
                        || previous.source() == DeviceTaskSource.UNKNOWN)) {
                    log.info("设备端外部打印已结束: printerId={}, previousFile={}, farmJobId={}",
                            printer.getId(), previous.deviceFilename(), previous.farmJobId());
                }
            }
        }
    }

    private String fileBasename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private boolean sameFilename(String left, String right) {
        return !left.isBlank() && !right.isBlank() && left.equalsIgnoreCase(right);
    }

    private boolean hasCompletionEvidence(PrinterDeviceStatus status) {
        if (status.progress() != null && status.progress().compareTo(BigDecimal.valueOf(100)) >= 0) {
            return true;
        }
        if (status.filePosition() != null && status.fileSize() != null
                && status.fileSize().signum() > 0
                && status.filePosition().compareTo(status.fileSize()) >= 0) {
            return true;
        }
        return status.timesLeft() != null && status.timesLeft().signum() == 0
                && status.filePosition() != null && status.filePosition().signum() > 0;
    }

    private boolean isRecentUploading(PrintJob job) {
        return PrintJobStatus.UPLOADING.name().equals(PrintJobStatus.normalize(job.getStatus()))
                && job.getUpdatedAt() != null
                && job.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(2));
    }

    private boolean finishJobFromDevice(Printer printer, PrintJob job,
                                        PrintJobStatus target, String errorReason) {
        if (!transitionFromDevice(job, target)) {
            return false;
        }
        job.setStatus(target.name());
        if (target == PrintJobStatus.COMPLETED) {
            job.setProgress(BigDecimal.valueOf(100));
        }
        job.setCompletedAt(LocalDateTime.now());
        job.setErrorReason(errorReason);
        if (!unbindPrinter(printer)) {
            return false;
        }
        log.info("设备终态同步完成，已解绑机器: jobId={}, printerId={}, status={}",
                job.getId(), printer.getId(), target);
        return true;
    }

    private boolean reconcileIdleJob(PrintJob job) {
        return reconcileIdleJob(job, "设备已返回空闲，无法确认任务终态，请人工核对");
    }

    private boolean reconcileIdleJob(PrintJob job, String reason) {
        String normalizedJobStatus = PrintJobStatus.normalize(job.getStatus());
        if (PrintJobStatus.PRINTING.name().equals(normalizedJobStatus)
                || PrintJobStatus.PAUSED.name().equals(normalizedJobStatus)
                || PrintJobStatus.UPLOADING.name().equals(normalizedJobStatus)
                || PrintJobStatus.READY.name().equals(normalizedJobStatus)) {
            if (transitionFromDevice(job, PrintJobStatus.RECONCILING)) {
                job.setStatus(PrintJobStatus.RECONCILING.name());
                job.setErrorReason(reason);
                return true;
            }
        }
        return false;
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

    private void recordCommunicationSuccess(Printer printer, PrinterDeviceStatus status) {
        long now = System.currentTimeMillis();
        boolean legacyWithoutHealthFields = printer.getConnectionStatus() == null
                && printer.getConsecutiveFailureCount() == null;
        int previousFailures = printer.getConsecutiveFailureCount() == null
                ? 0 : printer.getConsecutiveFailureCount();
        int successes = consecutiveSuccessCounts.merge(printer.getId(), 1, Integer::sum);
        boolean recovered = legacyWithoutHealthFields
                || successes >= Math.max(1, monitorProperties.getRecoverySuccessThreshold());
        boolean stateChanged = recovered && !"ONLINE".equalsIgnoreCase(printer.getConnectionStatus())
                || previousFailures != 0
                || printer.getLastCommunicationError() != null;
        String connectionStatus = recovered ? "ONLINE"
                : (printer.getConnectionStatus() == null ? "UNKNOWN" : printer.getConnectionStatus());
        printer.setConnectionStatus(connectionStatus);
        printer.setConsecutiveFailureCount(0);
        printer.setLastCommunicationError(null);
        printer.setLastKnownStatus(status.status() == null ? null : status.status().name());
        printer.setLastProtocolState(status.rawState());
        printer.setLastDeviceUptime(status.deviceUptime() == null ? null : status.deviceUptime().longValue());
        printer.setLastSeenAt(LocalDateTime.now());
        if (!legacyWithoutHealthFields
                && (stateChanged || now - lastHealthPersistAt.getOrDefault(printer.getId(), 0L) >= HEALTH_PERSIST_INTERVAL_MS)) {
            Printer updateEntity = new Printer();
            updateEntity.setId(printer.getId());
            updateEntity.setConnectionStatus(connectionStatus);
            updateEntity.setConsecutiveFailureCount(0);
            updateEntity.setLastCommunicationError(null);
            updateEntity.setLastKnownStatus(status.status() == null ? null : status.status().name());
            updateEntity.setLastProtocolState(status.rawState());
            updateEntity.setLastDeviceUptime(status.deviceUptime() == null ? null : status.deviceUptime().longValue());
            updateEntity.setLastSeenAt(printer.getLastSeenAt());
            if (printerCacheService.updatePrinterHealthWithLock(updateEntity)) {
                lastHealthPersistAt.put(printer.getId(), now);
            }
        }
        if (recovered) {
            printerCacheService.markPrinterOnline(printer.getId());
        }
    }

    private void handleCommunicationFailure(Printer printer, String errorMessage) {
        int failures = (printer.getConsecutiveFailureCount() == null ? 0 : printer.getConsecutiveFailureCount()) + 1;
        int threshold = Math.max(1, monitorProperties.getFailureThreshold());
        boolean legacyWithoutHealthFields = printer.getConnectionStatus() == null
                && printer.getConsecutiveFailureCount() == null;
        String error = errorMessage == null || errorMessage.isBlank() ? "设备状态查询失败" : errorMessage;
        if (error.length() > 255) {
            error = error.substring(0, 255);
        }
        printer.setConsecutiveFailureCount(failures);
        printer.setLastCommunicationError(error);
        consecutiveSuccessCounts.remove(printer.getId());
        // 兼容尚未执行 11 号迁移的旧测试/旧数据；完成迁移后按阈值防止瞬时网络抖动误报离线。
        boolean offline = legacyWithoutHealthFields || failures >= threshold;
        if (offline) {
            printer.setConnectionStatus("OFFLINE");
        }

        Printer updateEntity = new Printer();
        updateEntity.setId(printer.getId());
        updateEntity.setConsecutiveFailureCount(failures);
        updateEntity.setLastCommunicationError(error);
        updateEntity.setLastSeenAt(printer.getLastSeenAt());
        updateEntity.setLastKnownStatus(printer.getLastKnownStatus());
        updateEntity.setLastProtocolState(printer.getLastProtocolState());
        updateEntity.setLastDeviceUptime(printer.getLastDeviceUptime());
        if (offline) {
            updateEntity.setConnectionStatus("OFFLINE");
        }
        if (printerCacheService.updatePrinterHealthWithLock(updateEntity)) {
            lastHealthPersistAt.put(printer.getId(), System.currentTimeMillis());
        }
        if (offline) {
            printerCacheService.markPrinterOffline(printer.getId());
            printerCacheService.clearStatusCache(printer.getId());
        }
    }

    private String summarizeException(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
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
        if (result.skipped) {
            return;
        }
        String previousConnectionStatus = result.connectionStatus == null ? null
                : lastPublishedConnectionStatuses.put(result.printerId, result.connectionStatus);
        if (result.connectionStatus != null
                && !java.util.Objects.equals(previousConnectionStatus, result.connectionStatus)) {
            eventPublisher.publishPrinterHealth(result.printerId, result.connectionStatus,
                    result.lastKnownStatus, result.lastSeenAt, result.lastCommunicationError,
                    result.consecutiveFailureCount);
        }
        Boolean wasOnline = lastOnlineStates.put(result.printerId, result.online);
        if (!result.online) {
            lastPublishedStatuses.remove(result.printerId);
            lastPublishedTaskObservations.remove(result.printerId);
            if ("OFFLINE".equalsIgnoreCase(result.connectionStatus)
                    && !Boolean.FALSE.equals(wasOnline)) {
                eventPublisher.publishPrinterOffline(result.printerId, result.offlineReason);
            }
            return;
        }

        PrinterDeviceStatus previous = lastPublishedStatuses.put(result.printerId, result.status);
        TaskObservation previousObservation = result.taskObservation == null ? null
                : lastPublishedTaskObservations.put(result.printerId, result.taskObservation);
        if (!result.status.equals(previous)
                || !java.util.Objects.equals(result.taskObservation, previousObservation)) {
            TaskObservation observation = result.taskObservation;
            eventPublisher.publishPrinterStatus(result.printerId, result.status,
                    observation == null ? null : observation.source().name(),
                    observation == null ? null : observation.farmJobId(),
                    observation == null ? null : observation.farmJobStatus(),
                    observation == null ? null : observation.matchReason());
        }
    }

    private MoonrakerStatusDTO toLegacyStatus(PrinterDeviceStatus status, TaskObservation observation) {
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
        legacy.setTaskSource(observation.source().name());
        legacy.setFarmJobId(observation.farmJobId());
        legacy.setTaskMatchReason(observation.matchReason());
        legacy.setFarmJobStatus(observation.farmJobStatus());
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
        if (rawState == null || rawState.isBlank()) {
            return "unknown";
        }
        String normalized = rawState.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "processing" -> "printing";
            case "pausing" -> "paused";
            case "halted" -> "error";
            case "off", "disconnected" -> "offline";
            default -> normalized;
        };
    }

    private record PrinterStatusResult(Long printerId, PrinterDeviceStatus status,
                                       boolean online, String offlineReason,
                                       String connectionStatus, String lastKnownStatus,
                                       LocalDateTime lastSeenAt, String lastCommunicationError,
                                       Integer consecutiveFailureCount,
                                       boolean skipped, TaskObservation taskObservation) {
    }

    private record TaskObservation(DeviceTaskSource source, Long farmJobId,
                                   String farmJobStatus, String matchReason,
                                   String deviceFilename) {
    }

    private record TaskObservationContext(TaskObservation observation, PrintJob farmJob) {
    }

    private enum ObservedFileMatch {
        MATCHED,
        MISMATCHED,
        UNKNOWN
    }
}
