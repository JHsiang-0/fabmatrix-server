package com.example.farm.entity.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

/** 地址同步结果，不携带设备凭据或可执行请求。 */
public record PrinterIpSyncItemVO(@JsonFormat(shape = JsonFormat.Shape.STRING) Long printerId,
                                 String printerName, String oldIpAddress,
                                 String newIpAddress, String status, String message) {
}
