package com.example.farm.service;

import com.example.farm.controller.FarmStatusMessage;
import com.example.farm.controller.WebSocketServer;
import com.example.farm.protocol.PrinterDeviceStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 业务事件到 WebSocket 消息的唯一发布入口。
 */
@Service
public class WebSocketEventPublisher {

    public void publishPrinterStatus(Long printerId, PrinterDeviceStatus status) {
        if (printerId == null || status == null) {
            return;
        }
        WebSocketServer.broadcastPrinterStatus(FarmStatusMessage.printerStatus(printerId, status));
    }

    public void publishPrinterOffline(Long printerId, String reason) {
        if (printerId == null) {
            return;
        }
        String safeReason = reason == null || reason.isBlank() ? "设备无法连接" : reason;
        WebSocketServer.broadcastPrinterOffline(FarmStatusMessage.printerOffline(
                printerId, Map.of("status", "OFFLINE", "reason", safeReason)));
    }
}
