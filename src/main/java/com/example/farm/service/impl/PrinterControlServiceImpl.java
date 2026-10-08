package com.example.farm.service.impl;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.common.utils.PrinterLock;
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
import com.example.farm.service.PrinterIdentityVerifier;
import com.example.farm.service.AuditLogService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private RedisUtil redisUtil;

    @Autowired(required = false)
    private PrinterIdentityVerifier identityVerifier;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

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
        PrinterEndpoint endpoint = endpointOf(printer, PrinterOperation.EMERGENCY_STOP);
        PrinterLock.ensureValid(printerId);
        boolean deviceActionStarted = false;
        try {
            PrinterProtocolAdapter deviceAdapter = adapter(endpoint, PrinterOperation.EMERGENCY_STOP);
            deviceActionStarted = true;
            deviceAdapter.emergencyStop(endpoint);
            PrinterLock.ensureValid(printerId);
        } catch (BusinessException exception) {
            if (deviceActionStarted) {
                markDeviceActionUnknown(printer, "急停设备动作结果未知: " + summarizeException(exception));
                throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (!deviceActionStarted) {
                throw exception;
            }
            markDeviceActionUnknown(printer, "急停设备动作结果未知: " + summarizeException(exception));
            throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
        }
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
        recordSuccess("CONTROL_EMERGENCY_STOP", "PRINTER", printerId, printer.getName());
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
        Printer printer = endpointPrinter(printerId, PrinterOperation.PAUSE);
        PrinterEndpoint endpoint = endpointOf(printer, PrinterOperation.PAUSE);
        PrinterLock.ensureValid(printerId);
        boolean deviceActionStarted = false;
        try {
            PrinterProtocolAdapter deviceAdapter = adapter(endpoint, PrinterOperation.PAUSE);
            deviceActionStarted = true;
            deviceAdapter.pause(endpoint);
            PrinterLock.ensureValid(printerId);
        } catch (BusinessException exception) {
            if (deviceActionStarted) {
                markDeviceActionUnknown(printer, "暂停设备动作结果未知: " + summarizeException(exception));
                throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (!deviceActionStarted) {
                throw exception;
            }
            markDeviceActionUnknown(printer, "暂停设备动作结果未知: " + summarizeException(exception));
            throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
        }
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
        recordSuccess("CONTROL_PAUSE", "PRINTER", printerId, printer.getName());
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
        Printer printer = endpointPrinter(printerId, PrinterOperation.RESUME);
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

        PrinterEndpoint endpoint = endpointOf(printer, PrinterOperation.RESUME);
        PrinterLock.ensureValid(printerId);
        boolean deviceActionStarted = false;
        try {
            PrinterProtocolAdapter deviceAdapter = adapter(endpoint, PrinterOperation.RESUME);
            deviceActionStarted = true;
            deviceAdapter.resume(endpoint);
            PrinterLock.ensureValid(printerId);
        } catch (BusinessException exception) {
            if (deviceActionStarted) {
                markDeviceActionUnknown(printer, "恢复设备动作结果未知: " + summarizeException(exception));
                throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (!deviceActionStarted) {
                throw exception;
            }
            markDeviceActionUnknown(printer, "恢复设备动作结果未知: " + summarizeException(exception));
            throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
        }
        job.setStatus(PrintJobStatus.PRINTING.name());
        if (!printJobService.updateById(job)) {
            throw new BusinessException("恢复打印后更新任务状态失败");
        }
        printer.setStatus("PRINTING");
        if (!printerService.updateById(printer)) {
            throw new BusinessException("恢复打印后更新打印机状态失败");
        }
        eventPublisher.publishJobStatus(job);
        recordSuccess("CONTROL_RESUME", "PRINTER", printerId, printer.getName());
        log.info("恢复打印执行成功: printerId={}, jobId={}", printerId, jobId);
    }

    @Override
    public void cancelCurrentJob(Long printerId) {
        withPrinterLock(printerId, () -> {
            Printer printer = endpointPrinter(printerId, PrinterOperation.CANCEL);
            Long jobId = printer.getCurrentJobId();
            if (jobId == null) throw new BusinessException(422, "打印机当前没有绑定任务");
            PrintJob job = printJobService.getById(jobId);
            if (job == null) throw new BusinessException(404, "打印机绑定的任务不存在");
            if (!java.util.Objects.equals(job.getPrinterId(), printerId)) {
                throw new BusinessException(409, "打印机与当前任务绑定关系异常");
            }
            printJobService.cancelJob(jobId);
            log.info("取消打印机当前任务成功: printerId={}, jobId={}", printerId, jobId);
            return null;
        });
    }

    private PrinterEndpoint endpointOf(Printer printer, PrinterOperation operation) {
        PrinterProtocolType protocolType = PrinterProtocolType.normalize(printer.getFirmwareType());
        PrinterLock.ensureValid(printer.getId());
        if (identityVerifier != null) {
            identityVerifier.verifyFresh(printer);
        }
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
        return PrinterLock.execute(printerId, redisUtil, action);
    }

    private void markDeviceActionUnknown(Printer printer, String reason) {
        Runnable action = () -> {
            if (printer.getCurrentJobId() != null) {
                printJobService.markReconciliation(printer.getCurrentJobId(), reason);
            }
            Printer update = new Printer();
            update.setId(printer.getId());
            update.setStatus("ERROR");
            update.setIsSafeToPrint(false);
            printerService.updateById(update);
            recordFailure("CONTROL_DEVICE_ACTION_UNKNOWN", printer.getId(), reason);
        };
        if (transactionManager == null) {
            action.run();
        } else {
            org.springframework.transaction.support.TransactionTemplate template =
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> action.run());
        }
    }

    private String summarizeException(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private void recordFailure(String action, Long printerId, String reason) {
        if (auditLogService != null) {
            auditLogService.recordFailure(action, "PRINTER", printerId, "打印机 #" + printerId,
                    reason == null ? "DEVICE_ACTION_UNKNOWN" : reason);
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

    private void recordSuccess(String action, String targetType, Object targetId, String targetLabel) {
        if (auditLogService != null) {
            auditLogService.recordSuccess(action, targetType, targetId, targetLabel);
        }
    }
}
