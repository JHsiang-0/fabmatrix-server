package com.example.farm.service;

import com.example.farm.controller.FarmStatusMessage;
import com.example.farm.controller.WebSocketServer;
import com.example.farm.entity.PrintJob;
import com.example.farm.protocol.PrinterDeviceStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;
import com.example.farm.entity.enums.PrintJobStatus;

/**
 * 业务事件到 WebSocket 消息的唯一发布入口。
 */
@Service
public class WebSocketEventPublisher {

    /** 可选注入，便于 Local/单元测试；生产环境用于核对真实设备绑定。 */
    private PrinterService printerService;

    @Autowired(required = false)
    public void setPrinterService(PrinterService printerService) {
        this.printerService = printerService;
    }

    public void publishPrinterStatus(Long printerId, PrinterDeviceStatus status) {
        if (printerId == null || status == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unifiedState", status.status());
        data.put("state", status.rawState());
        data.put("systemMessage", status.systemMessage());
        data.put("filename", status.filename());
        data.put("progress", status.progress());
        data.put("toolTemperature", status.toolTemperature());
        data.put("toolTarget", status.toolTarget());
        data.put("bedTemperature", status.bedTemperature());
        data.put("bedTarget", status.bedTarget());
        data.put("printDuration", status.printDuration());
        data.put("totalDuration", status.totalDuration());
        data.put("filamentUsed", status.filamentUsed());
        data.put("filePosition", status.filePosition());
        data.put("fileSize", status.fileSize());
        data.put("timesLeft", status.timesLeft());
        data.put("lastFileCancelled", status.lastFileCancelled());
        data.put("lastFileAborted", status.lastFileAborted());
        data.put("lastDuration", status.lastDuration());
        data.put("deviceUptime", status.deviceUptime());
        data.put("connectionStatus", "ONLINE");
        data.put("lastKnownStatus", status.status());
        data.put("lastProtocolState", status.rawState());
        data.put("lastSeenAt", LocalDateTime.now());
        data.put("consecutiveFailureCount", 0);
        data.put("stateSource", "DEVICE_POLL");
        publishAfterCommit(() -> broadcastPrinterStatus(FarmStatusMessage.printerStatus(printerId, data)));
    }

    public void publishPrinterOffline(Long printerId, String reason) {
        if (printerId == null) {
            return;
        }
        String safeReason = reason == null || reason.isBlank() ? "设备无法连接" : reason;
        publishAfterCommit(() -> broadcastPrinterOffline(FarmStatusMessage.printerOffline(
                printerId, Map.of("status", "OFFLINE", "reason", safeReason))));
    }

    /** 推送通信健康变化；不改变现有设备实时状态消息契约。 */
    public void publishPrinterHealth(Long printerId, String connectionStatus,
                                     String lastKnownStatus, LocalDateTime lastSeenAt,
                                     String lastCommunicationError,
                                     Integer consecutiveFailureCount) {
        if (printerId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("connectionStatus", connectionStatus);
        data.put("lastKnownStatus", lastKnownStatus);
        data.put("lastSeenAt", lastSeenAt);
        data.put("lastCommunicationError", lastCommunicationError);
        data.put("consecutiveFailureCount", consecutiveFailureCount);
        data.put("stateSource", "COMMUNICATION_HEALTH");
        publishAfterCommit(() -> broadcastPrinterStatus(FarmStatusMessage.printerStatus(printerId, data)));
    }

    public void publishJobStatus(PrintJob job) {
        if (job == null || job.getId() == null) {
            return;
        }
        FarmStatusMessage message = createJobStatusMessage(job);
        publishAfterCommit(() -> broadcastJobStatus(message));
    }

    private FarmStatusMessage createJobStatusMessage(PrintJob job) {
        String status = PrintJobStatus.normalize(job.getStatus());
        if ("PREPARING".equals(status)) status = PrintJobStatus.UPLOADING.name();
        boolean terminal = PrintJobStatus.COMPLETED.name().equals(status)
                || PrintJobStatus.FAILED.name().equals(status)
                || PrintJobStatus.CANCELLED.name().equals(status);
        boolean clearsBinding = terminal || PrintJobStatus.QUEUED.name().equals(status);
        Long currentJobId = clearsBinding ? null : actualCurrentJobId(job);
        Map<String, Object> data = new LinkedHashMap<>();
        // FarmStatusMessage 负责把 Long 语义 ID 序列化为十进制字符串。
        data.put("jobId", String.valueOf(job.getId()));
        data.put("currentJobId", currentJobId == null ? null : String.valueOf(currentJobId));
        data.put("status", status);
        data.put("progress", job.getProgress());
        data.put("errorReason", job.getErrorReason());
        data.put("updatedAt", job.getUpdatedAt());
        return FarmStatusMessage.jobStatusForUser(job.getPrinterId(), job.getUserId(), data);
    }

    private Long actualCurrentJobId(PrintJob job) {
        if (job.getPrinterId() == null) return null;
        if (printerService == null) return job.getId();
        var printer = printerService.getById(job.getPrinterId());
        return printer != null && java.util.Objects.equals(printer.getCurrentJobId(), job.getId())
                ? job.getId() : null;
    }

    /** 布局保存提交后只广播版本变化，不携带或覆盖设备状态。 */
    public void publishLayoutUpdated(long layoutVersion) {
        publishAfterCommit(() -> broadcastLayoutUpdated(FarmStatusMessage.layoutUpdated(layoutVersion)));
    }

    /** 仅供事务 afterCommit 回调使用，避免在提交回调中再次注册同步器。 */
    public void publishLayoutUpdatedAfterCommit(long layoutVersion) {
        if (layoutVersion <= 0) {
            return;
        }
        broadcastLayoutUpdated(FarmStatusMessage.layoutUpdated(layoutVersion));
    }

    protected void broadcastPrinterStatus(FarmStatusMessage message) {
        WebSocketServer.broadcastPrinterStatus(message);
    }

    protected void broadcastPrinterOffline(FarmStatusMessage message) {
        WebSocketServer.broadcastPrinterOffline(message);
    }

    protected void broadcastJobStatus(FarmStatusMessage message) {
        WebSocketServer.broadcastJobStatus(message);
    }

    protected void broadcastLayoutUpdated(FarmStatusMessage message) {
        WebSocketServer.broadcastLayoutUpdated(message);
    }

    private void publishAfterCommit(Runnable publisher) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publisher.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.run();
            }
        });
    }
}
