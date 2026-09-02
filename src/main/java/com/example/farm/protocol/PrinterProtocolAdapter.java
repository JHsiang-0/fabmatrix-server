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

    void resume(PrinterEndpoint endpoint);

    void cancel(PrinterEndpoint endpoint);

    void emergencyStop(PrinterEndpoint endpoint);

    /**
     * 上传文件；startPrint 为 true 时要求协议端在上传成功后开始打印。
     */
    void uploadFile(PrinterEndpoint endpoint, Resource file, String filename, boolean startPrint);
}
