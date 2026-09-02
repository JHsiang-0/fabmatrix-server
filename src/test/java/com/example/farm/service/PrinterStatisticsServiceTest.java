package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterStatisticsQueryDTO;
import com.example.farm.entity.vo.PrinterStatisticsVO;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.impl.PrinterStatisticsServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrinterStatisticsServiceTest {

    @Test
    void returnsStatisticsForExistingPrinterAndEchoesRange() {
        PrintJobMapper jobMapper = mock(PrintJobMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        Printer printer = new Printer();
        printer.setId(403L);
        when(printerMapper.selectById(403L)).thenReturn(printer);
        PrinterStatisticsVO expected = new PrinterStatisticsVO();
        expected.setTotalJobs(4L);
        expected.setCompletedJobs(2L);
        expected.setFailedJobs(1L);
        expected.setCancelledJobs(1L);
        expected.setActiveJobs(0L);
        expected.setSuccessRate(new BigDecimal("66.67"));
        when(jobMapper.selectPrinterStatistics(org.mockito.ArgumentMatchers.eq(403L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);
        PrinterStatisticsQueryDTO query = new PrinterStatisticsQueryDTO();
        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 2, 0, 0);
        query.setFrom(from);
        query.setTo(to);

        PrinterStatisticsVO actual = new PrinterStatisticsServiceImpl(jobMapper, printerMapper)
                .getStatistics(403L, query);

        assertThat(actual.getPrinterId()).isEqualTo(403L);
        assertThat(actual.getTotalJobs()).isEqualTo(4L);
        assertThat(actual.getFrom()).isEqualTo(from);
        assertThat(actual.getTo()).isEqualTo(to);
    }

    @Test
    void rejectsUnknownPrinterAndReversedRange() {
        PrintJobMapper jobMapper = mock(PrintJobMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        PrinterStatisticsService service = new PrinterStatisticsServiceImpl(jobMapper, printerMapper);
        when(printerMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.getStatistics(404L, new PrinterStatisticsQueryDTO()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("打印机不存在");

        Printer printer = new Printer();
        printer.setId(403L);
        when(printerMapper.selectById(403L)).thenReturn(printer);
        PrinterStatisticsQueryDTO query = new PrinterStatisticsQueryDTO();
        query.setFrom(LocalDateTime.of(2026, 9, 2, 0, 0));
        query.setTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThatThrownBy(() -> service.getStatistics(403L, query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("开始时间不能晚于结束时间");
    }

    @Test
    void rejectsNonPositivePrinterIdAsValidationError() {
        PrinterStatisticsService service = new PrinterStatisticsServiceImpl(
                mock(PrintJobMapper.class), mock(PrinterMapper.class));

        assertThatThrownBy(() -> service.getStatistics(0L, new PrinterStatisticsQueryDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
    }
}
