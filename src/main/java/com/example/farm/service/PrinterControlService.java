package com.example.farm.service;

/**
 * 打印机硬件控制应用服务。
 */
public interface PrinterControlService {

    void emergencyStop(Long printerId);

    void pause(Long printerId);
}
