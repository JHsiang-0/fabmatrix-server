package com.example.farm.service;

import com.example.farm.common.constant.RedisKeyConstant;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.impl.PrinterCacheServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrinterCacheRedisTest {

    @Mock
    private RedisUtil redisUtil;
    @Mock
    private PrinterMapper printerMapper;
    @Mock
    private PrinterStatusHistoryService historyService;

    @Test
    void cachesPrinterStatusForTenSeconds() {
        PrinterCacheService service = new PrinterCacheServiceImpl(redisUtil, printerMapper, historyService);
        MoonrakerStatusDTO status = new MoonrakerStatusDTO();

        service.cachePrinterStatus(403L, status);

        verify(redisUtil).set(RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_STATUS, 403L),
                status, 10, TimeUnit.SECONDS);
    }

    @Test
    void skipsDatabaseUpdateWhenPrinterStatusLockIsUnavailable() {
        when(redisUtil.tryLock(anyString(), anyString(), eq(5L), eq(TimeUnit.SECONDS))).thenReturn(false);
        Printer printer = new Printer();
        printer.setId(403L);

        boolean updated = new PrinterCacheServiceImpl(redisUtil, printerMapper, historyService)
                .updatePrinterStatusWithLock(printer);

        assertThat(updated).isFalse();
        verify(printerMapper, never()).updateById(any(Printer.class));
    }

    @Test
    void explicitPrinterLockUsesFiveSecondRedisLease() {
        when(redisUtil.tryLock(anyString(), anyString(), eq(5L), eq(TimeUnit.SECONDS))).thenReturn(true);
        PrinterCacheService service = new PrinterCacheServiceImpl(redisUtil, printerMapper, historyService);

        assertThat(service.tryLockPrinterStatus(403L)).isTrue();

        verify(redisUtil).tryLock(eq(RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_LOCK, 403L)),
                anyString(), eq(5L), eq(TimeUnit.SECONDS));
    }
}
