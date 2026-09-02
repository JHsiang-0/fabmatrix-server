package com.example.farm.protocol;

/**
 * 协议无关的打印机业务状态。
 */
public enum PrinterStatus {
    OFFLINE,
    IDLE,
    PREPARING,
    PRINTING,
    PAUSED,
    ERROR,
    UNKNOWN
}
