package com.example.farm.protocol;

import org.springframework.core.io.Resource;

/**
 * 打印机协议适配器统一边界。
 *
 * 业务层只依赖该接口，不直接依赖 Moonraker 或 RRF 的具体客户端。
 */
public interface PrinterProtocolAdapter {

    PrinterProtocolType protocolType();

    PrinterDeviceStatus getStatus(PrinterEndpoint endpoint);

    void pause(PrinterEndpoint endpoint);

    default HeaterControlChannel inspectHeater(PrinterEndpoint endpoint, String heaterId) {
        throw new UnsupportedOperationException("HEATER_UNSUPPORTED");
    }

    default void setHeaterTarget(PrinterEndpoint endpoint, HeaterControlChannel channel, int targetC) {
        throw new UnsupportedOperationException("HEATER_UNSUPPORTED");
    }

    void resume(PrinterEndpoint endpoint);

    default RuntimeControlChannel inspectRuntimeControl(PrinterEndpoint endpoint, String action, String channelId, String toolId) {
        throw new UnsupportedOperationException("CONTROL_UNSUPPORTED");
    }

    default void setRuntimeControl(PrinterEndpoint endpoint, RuntimeControlChannel channel, int targetPercent) {
        throw new UnsupportedOperationException("CONTROL_UNSUPPORTED");
    }

    /** One final cancel write. Orchestration and terminal confirmation belong to the execution service. */
    default void requestCancel(PrinterEndpoint endpoint) { cancel(endpoint); }

    void cancel(PrinterEndpoint endpoint);

    void emergencyStop(PrinterEndpoint endpoint);

    /**
     * 上传文件；startPrint 为 true 时要求协议端在上传成功后开始打印。
     */
    void uploadFile(PrinterEndpoint endpoint, Resource file, String filename, boolean startPrint);

    /**
     * 启动设备端已经存在的文件，不再重复上传。
     */
    void startFile(PrinterEndpoint endpoint, String filename);
}
