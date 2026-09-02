package com.example.farm.protocol;

/**
 * 设备协议调用所需的内部连接信息。
 *
 * 该对象只能在服务端内部传递，禁止直接作为 REST 或 WebSocket 响应。
 */
public record PrinterEndpoint(
        Long printerId,
        String ipAddress,
        String apiKey,
        PrinterProtocolType protocolType
) {
}
