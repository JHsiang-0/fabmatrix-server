package com.example.farm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.common.utils.CacheStore;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.PrinterExecutionMapper;
import com.example.farm.mapper.PrinterMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/** 每台设备独立提交；锁覆盖校验与提交，成功审计和删除属于同一事务。 */
@Slf4j
@Service
public class PrinterBatchDeletionService {
    private static final int MAX_ITEMS = 100;
    private static final long RETRY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long RETRY_INTERVAL_MILLIS = 200;
    private final PrinterMapper printers;
    private final PrintJobMapper jobs;
    private final PrinterExecutionMapper executions;
    private final PrinterCacheService cache;
    private final AuditLogService audit;
    private final CacheStore cacheStore;
    private final TransactionTemplate transaction;

    public PrinterBatchDeletionService(PrinterMapper printers, PrintJobMapper jobs, PrinterExecutionMapper executions,
            PrinterCacheService cache, AuditLogService audit, CacheStore cacheStore, PlatformTransactionManager manager) {
        this.printers = printers;
        this.jobs = jobs;
        this.executions = executions;
        this.cache = cache;
        this.audit = audit;
        this.cacheStore = cacheStore;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Item(@JsonSerialize(using = ToStringSerializer.class) Long id, String name,
                       boolean success, String message) { }
    public record Result(int deletedCount, int failedCount, List<Item> items) { }

    public Result delete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_ITEMS
                || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(400, "请选择 1 至 100 台不同的有效设备");
        }
        Map<Long, Item> results = new LinkedHashMap<>();
        var pending = new ArrayList<>(ids);
        long deadline = System.nanoTime() + RETRY_WINDOW_NANOS;
        while (!pending.isEmpty()) {
            var busy = new ArrayList<Long>();
            for (Long id : pending) {
                try {
                    Item item = PrinterLock.tryExecute(id, cacheStore, () -> {
                        Item deleted = transaction.execute(status -> deleteOne(id));
                        try {
                            cache.clearStatusCache(id);
                            cache.refreshPrinterCache();
                        } catch (RuntimeException failure) {
                            log.warn("设备已删除但缓存清理失败: printerId={}", id);
                        }
                        return deleted;
                    }, 0);
                    results.put(id, item);
                } catch (PrinterLock.BusyException failure) {
                    busy.add(id);
                } catch (BusinessException failure) {
                    audit.recordFailure("PRINTER_DELETE", "PRINTER", id, null, String.valueOf(failure.getCode()));
                    results.put(id, new Item(id, null, false, failure.getMessage()));
                } catch (RuntimeException failure) {
                    log.error("批量删除设备失败: printerId={}", id, failure);
                    audit.recordFailure("PRINTER_DELETE", "PRINTER", id, null, "DELETE_FAILED");
                    results.put(id, new Item(id, null, false, "删除失败，请稍后重试"));
                }
            }
            if (busy.isEmpty()) break;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                pending = busy;
                break;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(RETRY_INTERVAL_MILLIS)));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                pending = busy;
                break;
            }
            pending = busy;
        }
        for (Long id : pending) {
            if (!results.containsKey(id)) {
                audit.recordFailure("PRINTER_DELETE", "PRINTER", id, null, "409");
                results.put(id, new Item(id, null, false, "设备操作繁忙，请稍后重试"));
            }
        }
        var items = ids.stream().map(results::get).toList();
        int deleted = (int) items.stream().filter(Item::success).count();
        return new Result(deleted, items.size() - deleted, List.copyOf(items));
    }

    private Item deleteOne(Long id) {
        PrinterLock.ensureValid(id);
        Printer printer = printers.selectById(id);
        if (printer == null) throw new BusinessException(404, "设备不存在或已删除");
        if (printer.getStatus() != null && List.of("PREPARING", "PRINTING", "PAUSED")
                .contains(printer.getStatus().toUpperCase(java.util.Locale.ROOT)) || printer.getCurrentJobId() != null) {
            throw new BusinessException(422, "设备正在执行任务或仍绑定任务");
        }
        Long activeJobs = jobs.selectCount(new LambdaQueryWrapper<PrintJob>().eq(PrintJob::getPrinterId, id)
                .and(query -> query.notIn(PrintJob::getStatus, "COMPLETED", "FAILED", "CANCELLED", "CANCELED")
                        .or().isNull(PrintJob::getStatus)));
        Long activeExecutions = executions.selectCount(new LambdaQueryWrapper<PrinterExecution>()
                .eq(PrinterExecution::getPrinterId, id).eq(PrinterExecution::getState, "ACTIVE"));
        if (activeJobs == null || activeExecutions == null) {
            throw new BusinessException(503, "无法确认设备任务状态，请稍后重试");
        }
        if (activeJobs > 0 || activeExecutions > 0) {
            throw new BusinessException(422, "设备仍有未结束的任务或执行记录");
        }
        PrinterLock.ensureValid(id);
        if (printers.delete(new LambdaQueryWrapper<Printer>().eq(Printer::getId, id)
                .and(query -> query.isNull(Printer::getStatus)
                        .or().notIn(Printer::getStatus, "PREPARING", "PRINTING", "PAUSED"))
                .isNull(Printer::getCurrentJobId)) != 1) {
            throw new BusinessException(409, "设备状态已变化，请刷新后重试");
        }
        audit.recordSuccess("PRINTER_DELETE", "PRINTER", id, printer.getName());
        PrinterLock.ensureValid(id);
        return new Item(id, printer.getName(), true, "删除成功");
    }
}
