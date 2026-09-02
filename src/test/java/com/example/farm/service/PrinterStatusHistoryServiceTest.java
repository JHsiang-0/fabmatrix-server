package com.example.farm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterStatusHistory;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.dto.PrinterHistoryQueryDTO;
import com.example.farm.mapper.PrinterStatusHistoryMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.service.impl.PrinterCacheServiceImpl;
import com.example.farm.service.impl.PrinterStatusHistoryServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class PrinterStatusHistoryServiceTest {

    @Test
    void recordsSafeStatusSample() {
        PrinterStatusHistoryMapper mapper = mock(PrinterStatusHistoryMapper.class);
        PrinterStatusHistoryService service = new PrinterStatusHistoryServiceImpl(
                mapper, mock(PrinterMapper.class));
        MoonrakerStatusDTO status = new MoonrakerStatusDTO();
        status.setUnifiedState("PRINTING");
        status.setState("printing");
        status.setProgress(42.5);
        status.setToolTemperature(210.0);

        service.record(403L, status);

        var captor = org.mockito.ArgumentCaptor.forClass(PrinterStatusHistory.class);
        verify(mapper).insert(captor.capture());
        PrinterStatusHistory saved = captor.getValue();
        assertThat(saved.getPrinterId()).isEqualTo(403L);
        assertThat(saved.getStatus()).isEqualTo("PRINTING");
        assertThat(saved.getRawState()).isEqualTo("printing");
        assertThat(saved.getProgress()).isEqualByComparingTo(new BigDecimal("42.5"));
        assertThat(saved.getToolTemperature()).isEqualByComparingTo(new BigDecimal("210.0"));
        assertThat(saved.getRecordedAt()).isNotNull();
    }

    @Test
    void pagesHistoryByPrinterAndTimeRange() {
        PrinterStatusHistoryMapper mapper = mock(PrinterStatusHistoryMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        Printer printer = new Printer();
        printer.setId(403L);
        when(printerMapper.selectById(403L)).thenReturn(printer);
        Page<PrinterStatusHistory> expected = new Page<>(1, 20);
        when(mapper.selectPage(any(Page.class), any())).thenReturn(expected);
        PrinterHistoryQueryDTO query = new PrinterHistoryQueryDTO();

        Page<PrinterStatusHistory> actual = new PrinterStatusHistoryServiceImpl(mapper, printerMapper)
                .page(403L, query);

        assertThat(actual).isSameAs(expected);
        verify(mapper).selectPage(any(Page.class), any());
    }

    @Test
    void rejectsUnknownPrinterAndInvalidTimeRange() {
        PrinterStatusHistoryMapper mapper = mock(PrinterStatusHistoryMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        PrinterStatusHistoryService service = new PrinterStatusHistoryServiceImpl(mapper, printerMapper);
        PrinterHistoryQueryDTO query = new PrinterHistoryQueryDTO();

        when(printerMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.page(404L, query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("打印机不存在");

        Printer printer = new Printer();
        printer.setId(403L);
        when(printerMapper.selectById(403L)).thenReturn(printer);
        query.setFrom(java.time.LocalDateTime.of(2026, 9, 2, 0, 0));
        query.setTo(java.time.LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThatThrownBy(() -> service.page(403L, query))
                .isInstanceOf(BusinessException.class)
                .hasMessage("开始时间不能晚于结束时间");
    }

    @Test
    void rejectsNonPositivePrinterIdAsValidationError() {
        PrinterStatusHistoryService service = new PrinterStatusHistoryServiceImpl(
                mock(PrinterStatusHistoryMapper.class), mock(PrinterMapper.class));

        assertThatThrownBy(() -> service.page(0L, new PrinterHistoryQueryDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
    }

    @Test
    void persistsFirstSampleAndStateChangesWithoutWritingEveryPoll() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        PrinterStatusHistoryService historyService = mock(PrinterStatusHistoryService.class);
        PrinterCacheServiceImpl cacheService = new PrinterCacheServiceImpl(
                redisUtil, printerMapper, historyService);
        MoonrakerStatusDTO status = new MoonrakerStatusDTO();
        status.setUnifiedState("IDLE");
        status.setState("standby");

        cacheService.recordStatusHistory(403L, status);
        cacheService.recordStatusHistory(403L, status);
        status.setUnifiedState("PRINTING");
        status.setState("printing");
        cacheService.recordStatusHistory(403L, status);

        verify(historyService, times(2)).record(403L, status);
    }
}
