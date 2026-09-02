package com.example.farm.controller;

/**
 * Farm WebSocket 的稳定消息类型。
 */
public enum FarmStatusMessageType {
    SNAPSHOT,
    PRINTER_STATUS,
    PRINTER_OFFLINE,
    JOB_STATUS
}
