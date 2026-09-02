package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.vo.PrinterDetailVO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrinterDetailServiceTest {

    @Test
    void returnsSafePrinterAndCurrentJobSummary() {
        PrinterService printerService = mock(PrinterService.class);
        PrinterCacheService cacheService = mock(PrinterCacheService.class);
        PrintJobService jobService = mock(PrintJobService.class);
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setName("Printer_C0DA");
        printer.setApiKey("must-not-leak");
        printer.setCurrentJobId(1001L);
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setPrinterId(403L);
        job.setStatus("PRINTING");
        MoonrakerStatusDTO status = new MoonrakerStatusDTO();
        status.setUnifiedState("PRINTING");
        when(printerService.getById(403L)).thenReturn(printer);
        when(jobService.getById(1001L)).thenReturn(job);
        when(cacheService.getCachedStatus(403L)).thenReturn(status);

        PrinterDetailVO detail = new PrinterDetailService(printerService, cacheService, jobService)
                .getDetail(403L);

        assertThat(detail.getPrinter().getName()).isEqualTo("Printer_C0DA");
        assertThat(detail.getPrinter()).hasFieldOrPropertyWithValue("id", 403L);
        assertThat(detail.getCurrentJob().getStatus()).isEqualTo("PRINTING");
        assertThat(detail.getRealtimeStatus().getUnifiedState()).isEqualTo("PRINTING");
        assertThat(detail.toString()).doesNotContain("must-not-leak");
    }

    @Test
    void returnsNullCurrentJobAndStatusWhenUnavailable() {
        PrinterService printerService = mock(PrinterService.class);
        PrinterCacheService cacheService = mock(PrinterCacheService.class);
        PrintJobService jobService = mock(PrintJobService.class);
        Printer printer = new Printer();
        printer.setId(403L);
        when(printerService.getById(403L)).thenReturn(printer);

        PrinterDetailVO detail = new PrinterDetailService(printerService, cacheService, jobService)
                .getDetail(403L);

        assertThat(detail.getRealtimeStatus()).isNull();
        assertThat(detail.getCurrentJob()).isNull();
    }

    @Test
    void rejectsMissingPrinter() {
        PrinterService printerService = mock(PrinterService.class);
        when(printerService.getById(403L)).thenReturn(null);

        assertThatThrownBy(() -> new PrinterDetailService(printerService,
                mock(PrinterCacheService.class), mock(PrintJobService.class)).getDetail(403L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("打印机不存在");
    }
}
