package com.example.farm.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 异步设备地址恢复的不可变快照；仅供管理员查看。 */
public record PrinterRediscoveryResultVO(String scanId, String status, String message,
                                         LocalDateTime startedAt, LocalDateTime finishedAt,
                                         List<String> subnets, int scannedAddressCount,
                                         int updatedCount, int unchangedCount, int unresolvedCount,
                                         List<PrinterIpSyncItemVO> items) {
    public PrinterRediscoveryResultVO {
        subnets = List.copyOf(subnets);
        items = List.copyOf(items);
    }
}
