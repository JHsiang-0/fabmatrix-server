package com.example.farm.protocol;

/**
 * 设备协议调用失败分类。
 */
public enum FailureCategory {
    OFFLINE,
    TIMEOUT,
    UNSUPPORTED,
    REJECTED,
    PROTOCOL_ERROR,
    UNKNOWN
}
