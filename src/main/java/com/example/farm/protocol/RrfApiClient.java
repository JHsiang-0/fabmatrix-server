package com.example.farm.protocol;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * RRF HTTP 客户端边界。
 *
 * <p>T2.2 只建立独立客户端边界。真实的 rr_connect、rr_model、rr_gcode
 * 和 rr_upload HTTP 请求留到 T2.3，在真实 RRF 设备参数确认后实现。</p>
 */
@Component
public class RrfApiClient {

    public RrfStatusResponse getStatus(PrinterEndpoint endpoint) {
        throw unsupported(PrinterOperation.GET_STATUS);
    }

    public void executeGcode(PrinterEndpoint endpoint, String gcode, PrinterOperation operation) {
        throw unsupported(operation);
    }

    public void uploadFile(PrinterEndpoint endpoint, Resource file, String filename, boolean startPrint) {
        throw unsupported(startPrint ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE);
    }

    private PrinterProtocolException unsupported(PrinterOperation operation) {
        return new PrinterProtocolException(
                operation,
                PrinterProtocolType.RRF,
                FailureCategory.UNSUPPORTED,
                "RRF 真实 HTTP 能力尚未实现，请先完成设备协议确认"
        );
    }
}
