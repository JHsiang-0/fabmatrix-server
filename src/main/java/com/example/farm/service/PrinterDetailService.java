package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.vo.PrinterDetailVO;
import com.example.farm.entity.vo.PrintJobVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 打印机详情查询服务。
 */
@Service
@RequiredArgsConstructor
public class PrinterDetailService {

    private final PrinterService printerService;
    private final PrinterCacheService printerCacheService;
    private final PrintJobService printJobService;
    private final PrinterStateSummaryService stateSummaries;

    public PrinterDetailVO getDetail(Long printerId) {
        if (printerId == null || printerId <= 0) {
            throw new BusinessException(400, "打印机 ID 必须为正数");
        }
        Printer printer = printerService.getById(printerId);
        if (printer == null) {
            throw new BusinessException(404, "打印机不存在");
        }

        PrintJob currentJob = printer.getCurrentJobId() == null
                ? null : printJobService.getById(printer.getCurrentJobId());
        var view = stateSummaries.view(printer);
        return new PrinterDetailVO(
                view,
                stateSummaries.realtime(printerCacheService.getCachedStatus(printerId), view.getStateSummary()),
                PrintJobVO.from(currentJob));
    }
}
