package com.example.farm.service.impl;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.constant.RedisKeyConstant;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterOperation;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolException;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.service.PrinterControlService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 通过协议适配器执行打印机控制操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterControlServiceImpl implements PrinterControlService {

    private final PrinterService printerService;
    private final PrinterProtocolAdapterFactory adapterFactory;
    private final PrintJobService printJobService;
    private final WebSocketEventPublisher eventPublisher;

    @Autowired(required = false)
    private RedisUtil redisUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void emergencyStop(Long printerId) {
        withPrinterLock(printerId, () -> {
            doEmergencyStop(printerId);
            return null;
        });
    }

    private void doEmergencyStop(Long printerId) {
        Printer printer = endpointPrinter(printerId, PrinterOperation.EMERGENCY_STOP);
        PrinterEndpoint endpoint = endpointOf(printerId, PrinterOperation.EMERGENCY_STOP);
        adapter(endpoint, PrinterOperation.EMERGENCY_STOP).emergencyStop(endpoint);
        Long jobId = printer.getCurrentJobId();
        if (jobId != null) {
            PrintJob job = boundJob(printer, jobId);
            if (transition(job, PrintJobStatus.RECONCILING)) {
                job.setStatus(PrintJobStatus.RECONCILING.name());
                job.setErrorReason("设备已执行急停，打印结果需要人工核对");
                if (!printJobService.updateById(job)) {
                    throw new BusinessException("急停后更新任务状态失败");
                }
                eventPublisher.publishJobStatus(job);
            }
        }
        printer.setStatus("ERROR");
        printer.setIsSafeToPrint(false);
        if (!printerService.updateById(printer)) {
            throw new BusinessException("急停后更新打印机状态失败");
        }
        log.warn("急停执行成功，设备已标记为 ERROR，等待人工核对: printerId={}, jobId={}", printerId, jobId);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void pause(Long printerId) {
        withPrinterLock(printerId, () -> {
            doPause(printerId);
            return null;
        });
    }

    private void doPause(Long printerId) {
        PrinterEndpoint endpoint = endpointOf(printerId, PrinterOperation.PAUSE);
        adapter(endpoint, PrinterOperation.PAUSE).pause(endpoint);
        Printer printer = printerService.getById(printerId);
        Long jobId = printer.getCurrentJobId();
        if (jobId != null) {
            PrintJob job = boundJob(printer, jobId);
            if (!transition(job, PrintJobStatus.PAUSED)) {
                throw new BusinessException(422, "当前任务不允许暂停");
            }
            job.setStatus(PrintJobStatus.PAUSED.name());
            if (!printJobService.updateById(job)) {
                throw new BusinessException("暂停后更新任务状态失败");
            }
            eventPublisher.publishJobStatus(job);
        }
        printer.setStatus("PAUSED");
        if (!printerService.updateById(printer)) {
            throw new BusinessException("暂停后更新打印机状态失败");
        }
        log.info("暂停打印执行成功: printerId={}", printerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resume(Long printerId) {
        withPrinterLock(printerId, () -> {
            doResume(printerId);
            return null;
        });
    }

    private void doResume(Long printerId) {
        PrinterEndpoint endpoint = endpointOf(printerId, PrinterOperation.RESUME);
        Printer printer = printerService.getById(printerId);
        Long jobId = printer.getCurrentJobId();
        if (jobId == null) {
            throw new BusinessException(422, "打印机当前没有绑定任务");
        }
        PrintJob job = printJobService.getById(jobId);
        if (job == null) {
            throw new BusinessException(404, "打印机绑定的任务不存在");
        }
        if (!java.util.Objects.equals(job.getPrinterId(), printerId)) {
            throw new BusinessException(409, "打印机与当前任务绑定关系异常");
        }
        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.PRINTING);

        adapter(endpoint, PrinterOperation.RESUME).resume(endpoint);
        job.setStatus(PrintJobStatus.PRINTING.name());
        if (!printJobService.updateById(job)) {
            throw new BusinessException("恢复打印后更新任务状态失败");
        }
        printer.setStatus("PRINTING");
        if (!printerService.updateById(printer)) {
            throw new BusinessException("恢复打印后更新打印机状态失败");
        }
        eventPublisher.publishJobStatus(job);
        log.info("恢复打印执行成功: printerId={}, jobId={}", printerId, jobId);
    }

    @Override
    public void cancelCurrentJob(Long printerId) {
        Printer printer = endpointPrinter(printerId, PrinterOperation.CANCEL);
        Long jobId = printer.getCurrentJobId();
        if (jobId == null) {
            throw new BusinessException(422, "打印机当前没有绑定任务");
        }
        PrintJob job = printJobService.getById(jobId);
        if (job == null) {
            throw new BusinessException(404, "打印机绑定的任务不存在");
        }
        if (!java.util.Objects.equals(job.getPrinterId(), printerId)) {
            throw new BusinessException(409, "打印机与当前任务绑定关系异常");
        }
        printJobService.cancelJob(jobId);
        log.info("取消打印机当前任务成功: printerId={}, jobId={}", printerId, jobId);
    }

    private PrinterEndpoint endpointOf(Long printerId, PrinterOperation operation) {
        Printer printer = endpointPrinter(printerId, operation);
        PrinterProtocolType protocolType = PrinterProtocolType.normalize(printer.getFirmwareType());
        return new PrinterEndpoint(printer.getId(), printer.getIpAddress(), printer.getApiKey(), protocolType);
    }

    private Printer endpointPrinter(Long printerId, PrinterOperation operation) {
        if (printerId == null || printerId <= 0) {
            throw new BusinessException(400, "打印机 ID 必须为正数");
        }
        Printer printer = printerService.getById(printerId);
        if (printer == null) {
            throw new BusinessException(404, "打印机不存在");
        }
        if ("OFFLINE".equalsIgnoreCase(printer.getStatus())) {
            throw new BusinessException(10001, "打印机当前离线");
        }
        if (printer.getIpAddress() == null || printer.getIpAddress().isBlank()) {
            throw new BusinessException(10001, "打印机没有可用的网络地址");
        }
        return printer;
    }

    private PrintJob boundJob(Printer printer, Long jobId) {
        PrintJob job = printJobService.getById(jobId);
        if (job == null) {
            throw new BusinessException(404, "打印机绑定的任务不存在");
        }
        if (!java.util.Objects.equals(job.getPrinterId(), printer.getId())) {
            throw new BusinessException(409, "打印机与当前任务绑定关系异常");
        }
        return job;
    }

    private boolean transition(PrintJob job, PrintJobStatus target) {
        try {
            PrintJobStatus.requireTransition(job.getStatus(), target);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private <T> T withPrinterLock(Long printerId, Supplier<T> action) {
        if (redisUtil == null) {
            return action.get();
        }
        if (printerId == null || printerId <= 0) {
            throw new BusinessException(400, "打印机 ID 必须为正数");
        }
        String key = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_LOCK, printerId);
        String value = UUID.randomUUID().toString();
        if (!redisUtil.tryLock(key, value, 30, TimeUnit.SECONDS)) {
            throw new BusinessException(409, "打印机正在被其他请求操作");
        }
        try {
            return action.get();
        } finally {
            if (value.equals(redisUtil.getLockValue(key))) {
                redisUtil.unlock(key);
            }
        }
    }

    private PrinterProtocolAdapter adapter(PrinterEndpoint endpoint, PrinterOperation operation) {
        try {
            return adapterFactory.getAdapter(endpoint.protocolType().name());
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("打印机协议选择失败: printerId={}, operation={}, protocol={}",
                    endpoint.printerId(), operation, endpoint.protocolType());
            throw new BusinessException(422, "打印机协议暂不支持当前操作");
        }
    }
}
