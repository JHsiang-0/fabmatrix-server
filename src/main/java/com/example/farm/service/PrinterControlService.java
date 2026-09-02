package com.example.farm.service;

/**
 * 打印机硬件控制应用服务。
 */
public interface PrinterControlService {

    void emergencyStop(Long printerId);

    void pause(Long printerId);

    void resume(Long printerId);

    /**
     * 取消该打印机当前绑定的农场任务。
     */
    void cancelCurrentJob(Long printerId);
}
