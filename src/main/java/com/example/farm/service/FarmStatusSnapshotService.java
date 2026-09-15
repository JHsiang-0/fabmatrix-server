package com.example.farm.service;

import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 构建 WebSocket 初始快照。
 *
 * <p>快照只使用安全 VO，不直接暴露打印机实体中的 apiKey；没有打印机时
 * 仍返回结构稳定的空列表。</p>
 */
@Service
@RequiredArgsConstructor
public class FarmStatusSnapshotService {

    private final PrinterService printerService;
    private final PrinterCacheService printerCacheService;

    public Map<String, Object> buildSnapshot() {
        var printerEntities = printerService.list();
        List<PrinterVO> printers = printerEntities.stream()
                .map(PrinterVO::from)
                .toList();
        List<Map<String, Object>> printerStatuses = printerEntities.stream()
                .filter(printer -> printer.getId() != null)
                .map(printer -> {
                    MoonrakerStatusDTO status = printerCacheService.getCachedStatus(printer.getId());
                    return status == null ? null : Map.<String, Object>of(
                            "printerId", String.valueOf(printer.getId()),
                            "data", status);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return Map.of("printers", printers, "printerStatuses", printerStatuses);
    }
}
