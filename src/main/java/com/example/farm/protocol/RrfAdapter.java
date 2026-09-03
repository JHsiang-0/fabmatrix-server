package com.example.farm.protocol;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * RepRapFirmware 协议适配器。
 *
 * <p>该类只负责 RRF 协议边界、错误语义和状态映射，不依赖 Moonraker。
 * 真实 HTTP 调用由 {@link RrfApiClient} 实现，适配器负责统一 Farm 协议边界。</p>
 */
@Component
@RequiredArgsConstructor
public class RrfAdapter implements PrinterProtocolAdapter {

    private final RrfApiClient rrfApiClient;

    @Override
    public PrinterProtocolType protocolType() {
        return PrinterProtocolType.RRF;
    }

    @Override
    public PrinterDeviceStatus getStatus(PrinterEndpoint endpoint) {
        validateEndpoint(endpoint, PrinterOperation.GET_STATUS);
        RrfStatusResponse source = rrfApiClient.getStatus(endpoint);
        if (source == null) {
            throw failure(PrinterOperation.GET_STATUS, FailureCategory.OFFLINE, "无法获取 RRF 打印机状态");
        }
        return toDeviceStatus(source);
    }

    @Override
    public void pause(PrinterEndpoint endpoint) {
        executeGcode(endpoint, PrinterOperation.PAUSE, "M25");
    }

    @Override
    public void resume(PrinterEndpoint endpoint) {
        executeGcode(endpoint, PrinterOperation.RESUME, "M24");
    }

    @Override
    public void cancel(PrinterEndpoint endpoint) {
        executeGcode(endpoint, PrinterOperation.CANCEL, "M0");
    }

    @Override
    public void emergencyStop(PrinterEndpoint endpoint) {
        executeGcode(endpoint, PrinterOperation.EMERGENCY_STOP, "M112");
    }

    @Override
    public void uploadFile(PrinterEndpoint endpoint, Resource file, String filename, boolean startPrint) {
        validateEndpoint(endpoint, startPrint ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE);
        if (file == null) {
            throw failure(startPrint ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE,
                    FailureCategory.REJECTED, "上传文件不能为空");
        }
        rrfApiClient.uploadFile(endpoint, file, filename, startPrint);
    }

    PrinterDeviceStatus toDeviceStatus(RrfStatusResponse source) {
        String stateStatus = normalize(source.stateStatus());
        String rawState = stateStatus;
        if ("unknown".equals(stateStatus)) {
            rawState = rawStateFromStatusCode(source.statusCode());
        }
        return new PrinterDeviceStatus(
                mapStatus(stateStatus, source.statusCode()),
                rawState,
                null,
                source.filename(),
                source.progress(),
                source.toolTemperature(),
                source.toolTarget(),
                source.bedTemperature(),
                source.bedTarget(),
                source.printDuration(),
                source.totalDuration(),
                source.filamentUsed()
        );
    }

    PrinterStatus mapStatus(String stateStatus, String statusCode) {
        String state = normalize(stateStatus);
        if (!"unknown".equals(state)) {
            return switch (state) {
                case "disconnected", "off" -> PrinterStatus.OFFLINE;
                case "starting", "updating", "simulating", "changingtool", "changing_tool",
                        "resuming", "cancelling", "busy" -> PrinterStatus.PREPARING;
                case "halted" -> PrinterStatus.ERROR;
                case "paused", "pausing" -> PrinterStatus.PAUSED;
                case "processing" -> PrinterStatus.PRINTING;
                case "idle" -> PrinterStatus.IDLE;
                default -> PrinterStatus.UNKNOWN;
            };
        }
        return mapStatusCode(statusCode);
    }

    private PrinterStatus mapStatusCode(String statusCode) {
        return switch (normalize(statusCode)) {
            case "o" -> PrinterStatus.OFFLINE;
            case "p" -> PrinterStatus.PRINTING;
            case "s" -> PrinterStatus.PAUSED;
            case "h" -> PrinterStatus.ERROR;
            case "c", "f", "d", "r", "t", "b" -> PrinterStatus.PREPARING;
            default -> PrinterStatus.UNKNOWN;
        };
    }

    private String rawStateFromStatusCode(String statusCode) {
        return switch (normalize(statusCode)) {
            case "o" -> "off";
            case "p" -> "processing";
            case "s" -> "paused";
            case "h" -> "halted";
            case "c" -> "starting";
            case "f" -> "updating";
            case "d" -> "pausing";
            case "r" -> "resuming";
            case "t" -> "changingTool";
            case "b" -> "busy";
            default -> "unknown";
        };
    }

    private void executeGcode(PrinterEndpoint endpoint, PrinterOperation operation, String gcode) {
        validateEndpoint(endpoint, operation);
        rrfApiClient.executeGcode(endpoint, gcode, operation);
    }

    private void validateEndpoint(PrinterEndpoint endpoint, PrinterOperation operation) {
        if (endpoint == null || endpoint.ipAddress() == null || endpoint.ipAddress().isBlank()) {
            throw failure(operation, FailureCategory.REJECTED, "打印机地址不能为空");
        }
        if (endpoint.protocolType() != PrinterProtocolType.RRF) {
            throw failure(operation, FailureCategory.PROTOCOL_ERROR, "RRF 适配器收到错误的协议类型");
        }
    }

    private PrinterProtocolException failure(PrinterOperation operation,
                                             FailureCategory category,
                                             String message) {
        return new PrinterProtocolException(operation, PrinterProtocolType.RRF, category, message);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }
}
