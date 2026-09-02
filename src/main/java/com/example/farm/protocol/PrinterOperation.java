package com.example.farm.protocol;

/**
 * 设备协议操作类型，用于错误分类和日志上下文。
 */
public enum PrinterOperation {
    GET_STATUS,
    PAUSE,
    RESUME,
    CANCEL,
    EMERGENCY_STOP,
    UPLOAD_FILE,
    START_PRINT
}
