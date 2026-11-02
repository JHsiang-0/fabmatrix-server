package com.example.farm.service;

import com.example.farm.common.constant.CacheKeyConstant;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.common.utils.CacheStore;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.Printer;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.protocol.observation.PrinterObservationView;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.impl.PrinterCacheServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

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
    private CacheStore cacheStore;
    @Mock
    private PrinterMapper printerMapper;
    @Mock
    private PrinterStatusHistoryService historyService;

    @Test
    void cachesPrinterStatusForTenSeconds() {
        PrinterCacheService service = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService);
        MoonrakerStatusDTO status = new MoonrakerStatusDTO();

        service.cachePrinterStatus(403L, status);

        verify(cacheStore).set(CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_STATUS, 403L),
                status, 10, TimeUnit.SECONDS);
    }

    @Test
    void cachesWholeObservationWithItsOriginalTimeBeyondIdlePollInterval() {
        PrinterCacheService service = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService);
        Instant observed = Instant.parse("2026-11-01T04:00:00Z");
        PrinterObservation observation = new PrinterObservation(observed, observed,
                PrinterObservation.Completeness.PARTIAL, PrinterObservation.SystemState.READY,
                PrinterObservation.OperationState.IDLE, List.of(), List.of(), null, null);
        service.cachePrinterObservation(403L, observation);
        verify(cacheStore).set(CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_OBSERVATION, 403L),
                observation, 90, TimeUnit.SECONDS);
    }

    @Test
    void cachedViewKeepsStaleObservationAsLastKnownOnly() {
        Instant now = Instant.parse("2026-11-01T04:00:31Z");
        Instant at = now.minusSeconds(31);
        PrinterObservation observation = new PrinterObservation(at, at, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("ONLINE");
        printer.setConsecutiveFailureCount(0);
        when(cacheStore.get(CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_OBSERVATION, 403L),
                PrinterObservation.class)).thenReturn(observation);

        PrinterObservationView view = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService)
                .getObservationView(printer, now, Duration.ofSeconds(30));

        assertThat(view.availability()).isEqualTo(PrinterObservationView.Availability.EXPIRED);
        assertThat(view.current()).isNull();
        assertThat(view.lastKnown()).isSameAs(observation);
    }

    @Test
    void skipsDatabaseUpdateWhenPrinterStatusLockIsUnavailable() {
        when(cacheStore.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(false);
        Printer printer = new Printer();
        printer.setId(403L);

        boolean updated = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService)
                .updatePrinterStatusWithLock(printer);

        assertThat(updated).isFalse();
        verify(printerMapper, never()).updateById(any(Printer.class));
    }

    @Test
    void reportsFailureWhenDatabaseStatusUpdateAffectsNoRows() {
        when(cacheStore.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(printerMapper.updateById(any(Printer.class))).thenReturn(0);
        Printer printer = new Printer();
        printer.setId(403L);

        boolean updated = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService)
                .updatePrinterStatusWithLock(printer);

        assertThat(updated).isFalse();
    }

    @Test
    void reportsSuccessOnlyWhenDatabaseStatusUpdateAffectsARow() {
        when(cacheStore.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(printerMapper.updateById(any(Printer.class))).thenReturn(1);
        Printer printer = new Printer();
        printer.setId(403L);

        boolean updated = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService)
                .updatePrinterStatusWithLock(printer);

        assertThat(updated).isTrue();
        verify(cacheStore).delete(CacheKeyConstant.PRINTER_LIST);
    }

    @Test
    void explicitPrinterLockUsesFiveSecondRedisLease() {
        when(cacheStore.tryLock(anyString(), anyString(), eq(5L), eq(TimeUnit.SECONDS))).thenReturn(true);
        PrinterCacheService service = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService);

        assertThat(service.tryLockPrinterStatus(403L)).isTrue();

        verify(cacheStore).tryLock(eq(CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_LOCK, 403L)),
                anyString(), eq(5L), eq(TimeUnit.SECONDS));
    }

    @Test
    void reentrantStatusUpdateReusesTheRenewedPrinterLock() {
        when(cacheStore.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(printerMapper.updateById(any(Printer.class))).thenReturn(1);
        Printer printer = new Printer();
        printer.setId(403L);
        PrinterCacheService service = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService);

        PrinterLock.execute(403L, cacheStore, () -> {
            assertThat(service.updatePrinterStatusWithLock(printer)).isTrue();
            return null;
        });

        verify(cacheStore).tryLock(eq(CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_LOCK, 403L)),
                anyString(), eq(60L), eq(TimeUnit.SECONDS));
        verify(cacheStore).releaseLockIfOwned(anyString(), anyString());
        verify(printerMapper).updateById(printer);
    }

    @Test
    void reentrantHealthUpdateReusesTheRenewedPrinterLock() {
        when(cacheStore.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(printerMapper.updateCommunicationHealth(any(Printer.class))).thenReturn(1);
        Printer printer = new Printer();
        printer.setId(403L);
        PrinterCacheService service = new PrinterCacheServiceImpl(cacheStore, printerMapper, historyService);

        PrinterLock.execute(403L, cacheStore, () -> {
            assertThat(service.updatePrinterHealthWithLock(printer)).isTrue();
            return null;
        });

        verify(cacheStore).tryLock(eq(CacheKeyConstant.getKey(CacheKeyConstant.PRINTER_LOCK, 403L)),
                anyString(), eq(60L), eq(TimeUnit.SECONDS));
        verify(cacheStore).releaseLockIfOwned(anyString(), anyString());
        verify(printerMapper).updateCommunicationHealth(printer);
    }
}
