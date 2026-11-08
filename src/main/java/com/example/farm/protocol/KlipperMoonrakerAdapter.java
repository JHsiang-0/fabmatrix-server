package com.example.farm.protocol;

import com.example.farm.common.utils.MoonrakerApiClient;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.protocol.observation.PrinterObservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Klipper/Moonraker 协议适配器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlipperMoonrakerAdapter implements PrinterProtocolAdapter {

    private final MoonrakerApiClient moonrakerApiClient;

    @Override
    public PrinterProtocolType protocolType() {
        return PrinterProtocolType.KLIPPER;
    }

    @Override
    public PrinterDeviceStatus getStatus(PrinterEndpoint endpoint) {
        validateEndpoint(endpoint, PrinterOperation.GET_STATUS);
        try {
            MoonrakerStatusDTO source = moonrakerApiClient.getPrinterStatus(endpoint);
            if (source == null) {
                throw failure(PrinterOperation.GET_STATUS, FailureCategory.OFFLINE, "无法获取打印机状态", null);
            }
            return toDeviceStatus(source);
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(PrinterOperation.GET_STATUS, classify(exception), "获取打印机状态失败", exception);
        }
    }

    @Override
    public void pause(PrinterEndpoint endpoint) {
        validateEndpoint(endpoint, PrinterOperation.PAUSE);
        executeBoolean(PrinterOperation.PAUSE, endpoint,
                () -> moonrakerApiClient.pausePrint(endpoint.ipAddress()), "暂停打印失败");
    }

    @Override public HeaterControlChannel inspectHeater(PrinterEndpoint endpoint, String heaterId) {
        validateEndpoint(endpoint, PrinterOperation.SET_HEATER_TARGET);
        return moonrakerApiClient.inspectHeater(endpoint, heaterId);
    }

    @Override public void setHeaterTarget(PrinterEndpoint endpoint, HeaterControlChannel channel, int targetC) {
        validateEndpoint(endpoint, PrinterOperation.SET_HEATER_TARGET);
        moonrakerApiClient.setHeaterTarget(endpoint, channel, targetC);
    }

    @Override
    public void resume(PrinterEndpoint endpoint) {
        validateEndpoint(endpoint, PrinterOperation.RESUME);
        executeBoolean(PrinterOperation.RESUME, endpoint,
                () -> moonrakerApiClient.resumePrint(endpoint.ipAddress()), "恢复打印失败");
    }

    @Override public RuntimeControlChannel inspectRuntimeControl(PrinterEndpoint endpoint, String action, String id, String toolId) {
        validateEndpoint(endpoint, PrinterOperation.valueOf(action));
        return moonrakerApiClient.inspectRuntimeControl(endpoint, action, id, toolId);
    }

    @Override public void setRuntimeControl(PrinterEndpoint endpoint, RuntimeControlChannel channel, int targetPercent) {
        validateEndpoint(endpoint, PrinterOperation.valueOf(channel.action()));
        moonrakerApiClient.setRuntimeControl(endpoint, channel, targetPercent);
    }

    @Override
    public void cancel(PrinterEndpoint endpoint) {
        validateEndpoint(endpoint, PrinterOperation.CANCEL);
        executeBoolean(PrinterOperation.CANCEL, endpoint,
                () -> moonrakerApiClient.cancelPrint(endpoint.ipAddress()), "取消打印失败");
    }

    @Override
    public void emergencyStop(PrinterEndpoint endpoint) {
        validateEndpoint(endpoint, PrinterOperation.EMERGENCY_STOP);
        executeBoolean(PrinterOperation.EMERGENCY_STOP, endpoint,
                () -> moonrakerApiClient.emergencyStop(endpoint.ipAddress()), "急停失败");
    }

    @Override
    public void uploadFile(PrinterEndpoint endpoint, Resource file, String filename, boolean startPrint) {
        validateEndpoint(endpoint, startPrint ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE);
        if (file == null) {
            throw failure(startPrint ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE,
                    FailureCategory.REJECTED, "上传文件不能为空", null);
        }
        PrinterOperation operation = startPrint ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE;
        try {
            moonrakerApiClient.uploadFile(endpoint.ipAddress(), endpoint.apiKey(), file, filename, startPrint);
        } catch (Exception exception) {
            throw failure(operation, classify(exception), "文件下发失败", exception);
        }
    }

    @Override
    public void startFile(PrinterEndpoint endpoint, String filename) {
        validateEndpoint(endpoint, PrinterOperation.START_PRINT);
        executeBoolean(PrinterOperation.START_PRINT, endpoint,
                () -> moonrakerApiClient.startPrint(endpoint.ipAddress(), endpoint.apiKey(), filename),
                "启动打印失败");
    }

    private PrinterDeviceStatus toDeviceStatus(MoonrakerStatusDTO source) {
        String systemState = normalize(source.getSystemState());
        String rawState = normalize(source.getState());
        PrinterStatus status = mapStatus(systemState, rawState);
        var execution = source.getObservation() == null ? null : source.getObservation().execution();
        return new PrinterDeviceStatus(
                status,
                source.getState(),
                source.getSystemMessage(),
                source.getFilename(),
                decimal(source.getProgress()),
                decimal(source.getToolTemperature()),
                decimal(source.getToolTarget()),
                decimal(source.getBedTemperature()),
                decimal(source.getBedTarget()),
                decimal(source.getPrintDuration()),
                decimal(source.getTotalDuration()),
                decimal(source.getFilamentUsed()),
                execution == null ? null : good(execution.filePositionBytes()),
                execution == null ? null : good(execution.fileSizeBytes()),
                null, null, null, null, null, source.getObservation()
        );
    }

    private BigDecimal good(PrinterObservation.Measurement measurement) {
        return measurement != null && measurement.quality() == PrinterObservation.Quality.GOOD
                ? measurement.value() : null;
    }

    private PrinterStatus mapStatus(String systemState, String rawState) {
        return switch (systemState) {
            case "shutdown", "error" -> PrinterStatus.ERROR;
            case "startup" -> PrinterStatus.PREPARING;
            case "ready" -> mapTaskStatus(rawState);
            default -> mapTaskStatus(rawState);
        };
    }

    private PrinterStatus mapTaskStatus(String rawState) {
        return switch (rawState) {
            case "printing" -> PrinterStatus.PRINTING;
            case "paused" -> PrinterStatus.PAUSED;
            case "standby", "complete", "ready", "cancelled" -> PrinterStatus.IDLE;
            case "error" -> PrinterStatus.ERROR;
            case "", "unknown" -> PrinterStatus.UNKNOWN;
            default -> PrinterStatus.UNKNOWN;
        };
    }

    private void executeBoolean(PrinterOperation operation,
                                PrinterEndpoint endpoint,
                                BooleanOperation action,
                                String message) {
        try {
            if (!action.execute()) {
                throw failure(operation, FailureCategory.OFFLINE, message, null);
            }
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(operation, classify(exception), message, exception);
        }
    }

    private void validateEndpoint(PrinterEndpoint endpoint, PrinterOperation operation) {
        if (endpoint == null || endpoint.ipAddress() == null || endpoint.ipAddress().isBlank()) {
            throw failure(operation, FailureCategory.REJECTED, "打印机地址不能为空", null);
        }
        if (endpoint.protocolType() != PrinterProtocolType.KLIPPER) {
            throw failure(operation, FailureCategory.PROTOCOL_ERROR, "Klipper 适配器收到错误的协议类型", null);
        }
    }

    private PrinterProtocolException failure(PrinterOperation operation,
                                             FailureCategory category,
                                             String message,
                                             Throwable cause) {
        return new PrinterProtocolException(operation, PrinterProtocolType.KLIPPER, category, message, cause);
    }

    private FailureCategory classify(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException) {
                return FailureCategory.TIMEOUT;
            }
            if (current instanceof java.io.IOException
                    || current instanceof java.net.ConnectException) {
                return FailureCategory.OFFLINE;
            }
            current = current.getCause();
        }
        return FailureCategory.PROTOCOL_ERROR;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    @FunctionalInterface
    private interface BooleanOperation {
        boolean execute() throws Exception;
    }
}
