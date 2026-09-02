package com.example.farm.service;

import com.example.farm.entity.vo.PrinterVO;
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

    public Map<String, Object> buildSnapshot() {
        List<PrinterVO> printers = printerService.list().stream()
                .map(PrinterVO::from)
                .toList();
        return Map.of("printers", printers);
    }
}
