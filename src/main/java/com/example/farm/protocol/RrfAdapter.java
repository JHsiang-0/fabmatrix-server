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

    private static final int CANCEL_CONFIRM_ATTEMPTS = 12;
    private static final long CANCEL_CONFIRM_DELAY_MILLIS = 500L;

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
        validateEndpoint(endpoint, PrinterOperation.CANCEL);
        PrinterDeviceStatus current = getStatus(endpoint);
        if (current.status() == PrinterStatus.IDLE) {
            return;
        }
        if (current.status() == PrinterStatus.PRINTING || current.status() == PrinterStatus.PREPARING) {
            executeGcode(endpoint, PrinterOperation.CANCEL, "M25");
            waitForState(endpoint, PrinterStatus.PAUSED, "暂停");
        } else if (current.status() != PrinterStatus.PAUSED) {
            throw failure(PrinterOperation.CANCEL, FailureCategory.REJECTED,
                    "RRF 当前状态不允许取消打印: " + current.rawState());
        }
        executeGcode(endpoint, PrinterOperation.CANCEL, "M0");
        waitForCancellationAccepted(endpoint);
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

    @Override
    public void startFile(PrinterEndpoint endpoint, String filename) {
        validateEndpoint(endpoint, PrinterOperation.START_PRINT);
        rrfApiClient.startFile(endpoint, filename);
    }

    private void waitForState(PrinterEndpoint endpoint, PrinterStatus expected, String action) {
        PrinterDeviceStatus latest = null;
        for (int attempt = 1; attempt <= CANCEL_CONFIRM_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepBeforeCancelConfirmation();
            }
            latest = getStatus(endpoint);
            boolean expectedStateReached = latest.status() == expected;
            if (expected == PrinterStatus.PAUSED) {
                expectedStateReached = expectedStateReached && "paused".equals(normalize(latest.rawState()));
            }
            if (expectedStateReached) {
                return;
            }
        }
        throw failure(PrinterOperation.CANCEL, FailureCategory.TIMEOUT,
                "RRF 未确认" + action + "完成，当前状态: "
                        + (latest == null ? "unknown" : latest.rawState()));
    }

    private void sleepBeforeCancelConfirmation() {
        try {
            Thread.sleep(CANCEL_CONFIRM_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PrinterProtocolException(PrinterOperation.CANCEL, PrinterProtocolType.RRF,
                    FailureCategory.UNKNOWN, "等待 RRF 取消确认时被中断", exception);
        }
    }

    private void waitForCancellationAccepted(PrinterEndpoint endpoint) {
        PrinterDeviceStatus latest = null;
        for (int attempt = 1; attempt <= CANCEL_CONFIRM_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepBeforeCancelConfirmation();
            }
            latest = getStatus(endpoint);
            String rawState = normalize(latest.rawState());
            if (latest.status() == PrinterStatus.IDLE || "cancelling".equals(rawState)) {
                return;
            }
        }
        throw failure(PrinterOperation.CANCEL, FailureCategory.TIMEOUT,
                "RRF 未确认取消命令已生效，当前状态: "
                        + (latest == null ? "unknown" : latest.rawState()));
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
                source.filamentUsed(),
                source.filePosition(),
                source.fileSize(),
                source.timesLeft(),
                source.lastFileCancelled(),
                source.lastFileAborted(),
                source.lastDuration(),
                source.deviceUptime()
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
