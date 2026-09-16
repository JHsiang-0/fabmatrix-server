package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.PrinterExecutionMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.service.PrinterExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterExecutionServiceImpl implements PrinterExecutionService {

    private static final String ACTIVE = "ACTIVE";

    private final PrinterExecutionMapper executionMapper;
    private final PrintJobMapper printJobMapper;
    private final PrinterMapper printerMapper;

    @Override
    public PrinterExecution findActive(Long printerId) {
        if (printerId == null) {
            return null;
        }
        return executionMapper.selectOne(new LambdaQueryWrapper<PrinterExecution>()
                .eq(PrinterExecution::getPrinterId, printerId)
                .eq(PrinterExecution::getState, ACTIVE)
                .orderByDesc(PrinterExecution::getId)
                .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrinterExecution beginFarmExecution(Long printerId, Long jobId, String expectedFilename,
                                               PrinterDeviceStatus confirmedStatus) {
        PrinterExecution active = findActive(printerId);
        if (active != null && (!"FARM".equals(active.getSource())
                || !Objects.equals(jobId, active.getFarmJobId()))) {
            close(active, "UNKNOWN", "新的平台任务取代未关闭的设备执行会话");
            active = null;
        }
        if (active == null) {
            active = newExecution(printerId, jobId, "FARM", expectedFilename);
        }
        mergeStatus(active, confirmedStatus);
        save(active);
        return active;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrinterExecution observeExecuting(Long printerId, Long farmJobId, String source,
                                             String expectedFilename, PrinterDeviceStatus status) {
        String normalizedSource = normalizeSource(source);
        PrinterExecution active = findActive(printerId);
        if (active != null && (!Objects.equals(active.getFarmJobId(), farmJobId)
                || !Objects.equals(active.getSource(), normalizedSource))) {
            close(active, "UNKNOWN", "设备执行来源或任务发生变化");
            active = null;
        }
        if (active == null) {
            active = newExecution(printerId, farmJobId, normalizedSource, expectedFilename);
        }
        if (active.getExpectedFilename() == null || active.getExpectedFilename().isBlank()) {
            active.setExpectedFilename(expectedFilename);
        }
        mergeStatus(active, status);
        active.setIdleObservationCount(0);
        save(active);
        return active;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrinterExecution observeIdle(Long printerId, PrinterDeviceStatus status) {
        PrinterExecution active = findActive(printerId);
        if (active == null) {
            return null;
        }
        active.setIdleObservationCount((active.getIdleObservationCount() == null
                ? 0 : active.getIdleObservationCount()) + 1);
        active.setLastRawState(status == null ? "idle" : status.rawState());
        active.setLastSeenAt(LocalDateTime.now());
        active.setUpdatedAt(LocalDateTime.now());
        if (status != null && status.deviceUptime() != null) {
            active.setDeviceUptimeLast(status.deviceUptime().longValue());
        }
        save(active);
        return active;
    }

    @Override
    public boolean deviceRestarted(PrinterExecution execution, PrinterDeviceStatus status) {
        if (execution == null || status == null || status.deviceUptime() == null
                || execution.getDeviceUptimeAtStart() == null) {
            return false;
        }
        return status.deviceUptime().longValue() < execution.getDeviceUptimeAtStart();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean finishFarmExecution(PrinterExecution execution, Printer printer, PrintJob job,
                                       PrintJobStatus target, String reason) {
        if (execution == null || printer == null || job == null || target == null
                || !ACTIVE.equals(execution.getState())
                || !"FARM".equals(execution.getSource())
                || !Objects.equals(execution.getFarmJobId(), job.getId())
                || !Objects.equals(execution.getPrinterId(), printer.getId())) {
            return false;
        }

        PrintJob persistedJob = printJobMapper.selectById(job.getId());
        Printer persistedPrinter = printerMapper.selectById(printer.getId());
        if (persistedJob == null || persistedPrinter == null
                || !Objects.equals(persistedJob.getPrinterId(), persistedPrinter.getId())
                || !Objects.equals(persistedPrinter.getCurrentJobId(), persistedJob.getId())) {
            return false;
        }

        PrintJobStatus.requireTransition(persistedJob.getStatus(), target);
        LocalDateTime now = LocalDateTime.now();
        persistedJob.setStatus(target.name());
        persistedJob.setCompletedAt(now);
        persistedJob.setErrorReason(reason);
        if (target == PrintJobStatus.COMPLETED) {
            persistedJob.setProgress(BigDecimal.valueOf(100));
        }
        if (printJobMapper.updateById(persistedJob) <= 0) {
            throw new BusinessException(500, "任务终态保存失败");
        }
        if (printerMapper.clearJobBinding(persistedPrinter.getId(), persistedJob.getId()) <= 0) {
            throw new BusinessException(500, "任务终态保存失败：打印机解绑失败");
        }
        close(execution, target.name(), reason);

        job.setStatus(persistedJob.getStatus());
        job.setProgress(persistedJob.getProgress());
        job.setCompletedAt(persistedJob.getCompletedAt());
        job.setErrorReason(persistedJob.getErrorReason());
        printer.setCurrentJobId(null);
        printer.setIsSafeToPrint(false);
        printer.setStatus("IDLE");
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean finishNonFarmExecution(PrinterExecution execution, String state, String reason) {
        if (execution == null || !ACTIVE.equals(execution.getState()) || "FARM".equals(execution.getSource())) {
            return false;
        }
        close(execution, state == null || state.isBlank() ? "COMPLETED" : state, reason);
        return true;
    }

    private PrinterExecution newExecution(Long printerId, Long farmJobId, String source, String expectedFilename) {
        LocalDateTime now = LocalDateTime.now();
        PrinterExecution execution = new PrinterExecution();
        execution.setPrinterId(printerId);
        execution.setFarmJobId(farmJobId);
        execution.setSource(source);
        execution.setState(ACTIVE);
        execution.setExpectedFilename(expectedFilename);
        execution.setHasSeenProcessing(false);
        execution.setHasSeenPaused(false);
        execution.setIdleObservationCount(0);
        execution.setStartedAt(now);
        execution.setLastSeenAt(now);
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        return execution;
    }

    private void mergeStatus(PrinterExecution execution, PrinterDeviceStatus status) {
        if (status == null) {
            return;
        }
        String rawState = normalize(status.rawState());
        execution.setLastRawState(status.rawState());
        execution.setObservedFilename(status.filename());
        execution.setHasSeenProcessing(Boolean.TRUE.equals(execution.getHasSeenProcessing())
                || "processing".equals(rawState) || "printing".equals(rawState));
        execution.setHasSeenPaused(Boolean.TRUE.equals(execution.getHasSeenPaused())
                || "paused".equals(rawState) || "pausing".equals(rawState));
        execution.setMaxProgress(max(execution.getMaxProgress(), status.progress()));
        execution.setMaxFilePosition(max(execution.getMaxFilePosition(), status.filePosition()));
        if (status.fileSize() != null && status.fileSize().signum() > 0) {
            execution.setFileSize(status.fileSize());
        }
        if (status.deviceUptime() != null) {
            long uptime = status.deviceUptime().longValue();
            if (execution.getDeviceUptimeAtStart() == null) {
                execution.setDeviceUptimeAtStart(uptime);
            }
            execution.setDeviceUptimeLast(uptime);
        }
        execution.setLastSeenAt(LocalDateTime.now());
        execution.setUpdatedAt(LocalDateTime.now());
    }

    private void close(PrinterExecution execution, String state, String reason) {
        execution.setState(state);
        execution.setEndReason(truncate(reason));
        execution.setEndedAt(LocalDateTime.now());
        execution.setUpdatedAt(LocalDateTime.now());
        if (executionMapper.updateById(execution) <= 0) {
            throw new BusinessException(500, "设备执行会话关闭失败");
        }
    }

    private void save(PrinterExecution execution) {
        int affected = execution.getId() == null
                ? executionMapper.insert(execution) : executionMapper.updateById(execution);
        if (affected <= 0) {
            throw new BusinessException(500, "设备执行会话保存失败");
        }
    }

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.max(right);
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) return "UNKNOWN";
        String normalized = source.trim().toUpperCase();
        return switch (normalized) {
            case "FARM", "EXTERNAL", "UNKNOWN" -> normalized;
            default -> "UNKNOWN";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String truncate(String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }
}
