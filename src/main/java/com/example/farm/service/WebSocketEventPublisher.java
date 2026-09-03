package com.example.farm.service;

import com.example.farm.controller.FarmStatusMessage;
import com.example.farm.controller.WebSocketServer;
import com.example.farm.entity.PrintJob;
import com.example.farm.protocol.PrinterDeviceStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 业务事件到 WebSocket 消息的唯一发布入口。
 */
@Service
public class WebSocketEventPublisher {

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
        data.put("stateSource", "DEVICE_POLL");
        FarmStatusMessage message = FarmStatusMessage.printerStatus(printerId, data);
        publishAfterCommit(() -> broadcastPrinterStatus(message));
    }

    public void publishPrinterOffline(Long printerId, String reason) {
        if (printerId == null) {
            return;
        }
        String safeReason = reason == null || reason.isBlank() ? "设备无法连接" : reason;
        FarmStatusMessage message = FarmStatusMessage.printerOffline(
                printerId, Map.of("status", "OFFLINE", "reason", safeReason));
        publishAfterCommit(() -> broadcastPrinterOffline(message));
    }

    public void publishJobStatus(PrintJob job) {
        if (job == null || job.getId() == null || job.getPrinterId() == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.getId());
        data.put("currentJobId", job.getId());
        data.put("status", job.getStatus());
        data.put("progress", job.getProgress());
        data.put("errorReason", job.getErrorReason());
        FarmStatusMessage message = FarmStatusMessage.jobStatus(job.getPrinterId(), data);
        publishAfterCommit(() -> broadcastJobStatus(message));
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
