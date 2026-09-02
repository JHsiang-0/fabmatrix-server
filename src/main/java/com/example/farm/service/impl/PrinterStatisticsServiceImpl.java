package com.example.farm.service.impl;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterStatisticsQueryDTO;
import com.example.farm.entity.vo.PrinterStatisticsVO;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.PrinterStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 打印机任务统计服务实现。
 */
@Service
@RequiredArgsConstructor
public class PrinterStatisticsServiceImpl implements PrinterStatisticsService {

    private final PrintJobMapper printJobMapper;
    private final PrinterMapper printerMapper;

    @Override
    public PrinterStatisticsVO getStatistics(Long printerId, PrinterStatisticsQueryDTO query) {
        if (printerId == null || printerId <= 0) {
            throw new BusinessException("打印机 ID 必须为正数");
        }
        Printer printer = printerMapper.selectById(printerId);
        if (printer == null) {
            throw new BusinessException(404, "打印机不存在");
        }
        PrinterStatisticsQueryDTO actualQuery = query == null
                ? new PrinterStatisticsQueryDTO() : query;
        if (actualQuery.getFrom() != null && actualQuery.getTo() != null
                && actualQuery.getFrom().isAfter(actualQuery.getTo())) {
            throw new BusinessException("开始时间不能晚于结束时间");
        }

        PrinterStatisticsVO statistics = printJobMapper.selectPrinterStatistics(
                printerId, actualQuery.getFrom(), actualQuery.getTo());
        if (statistics == null) {
            statistics = new PrinterStatisticsVO();
        }
        statistics.setPrinterId(printerId);
        statistics.setFrom(actualQuery.getFrom());
        statistics.setTo(actualQuery.getTo());
        return statistics;
    }
}
