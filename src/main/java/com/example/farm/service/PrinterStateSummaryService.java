package com.example.farm.service;

import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.model.CanonicalPrinterSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.beans.BeanUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** 统一模型到本地列表及实时摘要的读取入口。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrinterStateSummaryService {
    private final PrinterMapper printers;
    private final ObjectProvider<CanonicalPrinterStateService> states;

    public CanonicalPrinterSummary get(Long printerId) {
        Printer printer = printers.selectById(printerId);
        return printer == null ? null : views(List.of(printer)).getFirst().getStateSummary();
    }

    public PrinterVO view(Printer printer) {
        return printer == null ? null : views(List.of(printer)).getFirst();
    }

    public MoonrakerStatusDTO realtime(MoonrakerStatusDTO legacy, CanonicalPrinterSummary summary) {
        if (legacy == null && summary == null) { return null; }
        var response = new MoonrakerStatusDTO();
        if (legacy != null) { BeanUtils.copyProperties(legacy, response); }
        response.setStateSummary(summary);
        return response;
    }

    public List<PrinterVO> views(List<Printer> items) {
        var service = states.getIfAvailable();
        Map<Long, CanonicalPrinterModel> models = Map.of();
        if (service != null && !items.isEmpty()) {
            try { models = service.findLatestBatch(items); }
            catch (RuntimeException failure) {
                log.warn("设备状态摘要读取失败，已隐藏实时值: errorType={}", failure.getClass().getSimpleName());
            }
        }
        Map<Long, CanonicalPrinterModel> captured = models;
        Instant now = Instant.now();
        return items.stream().map(printer -> {
            var view = PrinterVO.from(printer);
            if (printer.getId() == null) { return view; }
            var model = captured.get(printer.getId());
            var summary = model == null ? unavailable(printer, now) : CanonicalPrinterSummary.from(model, now);
            view.setStateSummary(summary);
            return view;
        }).toList();
    }

    private CanonicalPrinterSummary unavailable(Printer printer, Instant now) {
        Instant lastSeen = printer.getLastSeenAt() == null ? null
                : printer.getLastSeenAt().atZone(ZoneId.systemDefault()).toInstant();
        return CanonicalPrinterSummary.unavailable(printer.getId().toString(), Boolean.TRUE.equals(printer.getMonitorEnabled()),
                printer.getConnectionStatus(), lastSeen,
                printer.getConsecutiveFailureCount() == null ? 0 : printer.getConsecutiveFailureCount(), now);
    }
}
