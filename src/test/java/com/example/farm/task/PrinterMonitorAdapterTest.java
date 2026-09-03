package com.example.farm.task;

import com.example.farm.entity.Printer;
import com.example.farm.entity.PrintJob;
import com.example.farm.config.PrinterMonitorProperties;
import com.example.farm.protocol.FailureCategory;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterOperation;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolException;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.WebSocketEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PrinterMonitorAdapterTest {

    @Mock
    private PrinterService printerService;
    @Mock
    private PrinterCacheService printerCacheService;
    @Mock
    private PrinterProtocolAdapterFactory adapterFactory;
    @Mock
    private PrinterProtocolAdapter adapter;
    @Mock
    private PrintJobService printJobService;
    @Mock
    private WebSocketEventPublisher eventPublisher;

    private PrinterMonitorTask monitorTask;

    @AfterEach
    void stopMonitorExecutor() {
        if (monitorTask != null) {
            monitorTask.destroy();
        }
    }

    @Test
    void pollsStatusThroughSelectedAdapter() {
        Printer printer = printer("Klipper");
        when(printerService.listByIds(anyList())).thenReturn(List.of(printer));
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("Klipper")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenReturn(printingStatus());
        monitorTask = new PrinterMonitorTask(printerService, printerCacheService, adapterFactory, printJobService,
                eventPublisher, monitorProperties());

        monitorTask.checkPrinterStatus();

        verify(adapterFactory, timeout(1000)).getAdapter("Klipper");
        verify(adapter, timeout(1000)).getStatus(any());
        verify(printerCacheService, timeout(1000)).cachePrinterStatus(any(), any());
    }

    @Test
    void treatsAdapterFailureAsOfflineAndClearsCachedStatus() {
        Printer printer = printer("RRF");
        when(printerService.listByIds(anyList())).thenReturn(List.of(printer));
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenThrow(new PrinterProtocolException(
                PrinterOperation.GET_STATUS,
                PrinterProtocolType.RRF,
                FailureCategory.TIMEOUT,
                "设备状态查询超时"));
        monitorTask = new PrinterMonitorTask(printerService, printerCacheService, adapterFactory, printJobService,
                eventPublisher, monitorProperties());

        monitorTask.checkPrinterStatus();

        verify(printerCacheService, timeout(1000)).markPrinterOffline(403L);
        verify(printerCacheService, timeout(1000)).clearStatusCache(403L);
        verify(eventPublisher, timeout(1000)).publishPrinterOffline(403L, "设备状态查询失败");
    }

    @Test
    void doesNotPublishRepeatedOfflineEvents() {
        Printer printer = printer("RRF");
        when(printerService.listByIds(anyList())).thenReturn(List.of(printer));
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenThrow(new PrinterProtocolException(
                PrinterOperation.GET_STATUS,
                PrinterProtocolType.RRF,
                FailureCategory.OFFLINE,
                "设备离线"));
        monitorTask = new PrinterMonitorTask(printerService, printerCacheService, adapterFactory, printJobService,
                eventPublisher, monitorProperties());

        monitorTask.checkPrinterStatus();
        verify(eventPublisher, timeout(1000)).publishPrinterOffline(403L, "设备状态查询失败");

        monitorTask.checkPrinterStatus();
        verify(eventPublisher, timeout(1000).times(1))
                .publishPrinterOffline(403L, "设备状态查询失败");
    }

    @Test
    void skipsOverlappingScansWhilePreviousScanIsRunning() throws InterruptedException {
        Printer printer = printer("RRF");
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        when(printerService.listByIds(anyList())).thenReturn(List.of(printer));
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenAnswer(invocation -> {
            queryStarted.countDown();
            releaseQuery.await(2, TimeUnit.SECONDS);
            return printingStatus();
        });
        monitorTask = new PrinterMonitorTask(printerService, printerCacheService, adapterFactory, printJobService,
                eventPublisher, monitorProperties());

        monitorTask.checkPrinterStatus();
        assertThat(queryStarted.await(1, TimeUnit.SECONDS)).isTrue();
        monitorTask.checkPrinterStatus();
        releaseQuery.countDown();

        verify(adapter, timeout(1000).times(1)).getStatus(any());
    }

    @Test
    void doesNotFinishJobWhenPrinterUnbindCannotBeSaved() {
        Printer printer = printer("RRF");
        printer.setStatus("PRINTING");
        printer.setCurrentJobId(1001L);
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setStatus("PRINTING");
        when(printerService.listByIds(anyList())).thenReturn(List.of(printer));
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenReturn(completedStatus());
        when(printJobService.getById(1001L)).thenReturn(job);
        when(printerService.clearJobBinding(403L, 1001L)).thenReturn(false);
        monitorTask = new PrinterMonitorTask(printerService, printerCacheService, adapterFactory, printJobService,
                eventPublisher, monitorProperties());

        monitorTask.checkPrinterStatus();

        verify(printJobService, timeout(1000)).getById(1001L);
        verify(printJobService, never()).updateById(any(PrintJob.class));
        verify(eventPublisher, never()).publishJobStatus(any(PrintJob.class));
    }

    @Test
    void skipsScanWhenMonitorWhitelistIsEmpty() {
        PrinterMonitorProperties properties = new PrinterMonitorProperties();
        monitorTask = new PrinterMonitorTask(printerService, printerCacheService, adapterFactory, printJobService,
                eventPublisher, properties);

        monitorTask.checkPrinterStatus();

        verify(printerService, never()).listByIds(anyList());
        verifyNoInteractions(adapterFactory);
    }

    @Test
    void refreshesBindingFromDatabaseBeforeDeviceStatusHandling() {
        Printer listedPrinter = printer("RRF");
        Printer latestPrinter = printer("RRF");
        latestPrinter.setCurrentJobId(1001L);
        latestPrinter.setStatus("PRINTING");
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setStatus("PRINTING");

        when(printerService.listByIds(anyList())).thenReturn(List.of(listedPrinter));
        when(printerService.getById(403L)).thenReturn(latestPrinter);
        when(adapterFactory.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenReturn(printingStatus());
        when(printJobService.getById(1001L)).thenReturn(job);
        monitorTask = new PrinterMonitorTask(printerService, printerCacheService, adapterFactory, printJobService,
                eventPublisher, monitorProperties());

        monitorTask.checkPrinterStatus();

        verify(printJobService, timeout(1000)).getById(1001L);
    }

    @Test
    void doesNotTreatIdleDeviceAsCompletedWhenFarmJobIsPrinting() {
        Printer printer = printer("RRF");
        printer.setCurrentJobId(1001L);
        printer.setStatus("PRINTING");
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setStatus("PRINTING");

        when(printerService.listByIds(anyList())).thenReturn(List.of(printer));
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenReturn(idleStatus());
        when(printJobService.getById(1001L)).thenReturn(job);
        when(printJobService.updateById(any(PrintJob.class))).thenReturn(true);
        monitorTask = new PrinterMonitorTask(printerService, printerCacheService, adapterFactory, printJobService,
                eventPublisher, monitorProperties());

        monitorTask.checkPrinterStatus();

        verify(printJobService, timeout(1000)).updateById(job);
        assertThat(job.getStatus()).isEqualTo("RECONCILING");
        verify(printerService, never()).clearJobBinding(403L, 1001L);
    }

    private Printer printer(String firmwareType) {
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setName("Printer_C0DA");
        printer.setIpAddress("192.168.1.80");
        printer.setFirmwareType(firmwareType);
        printer.setStatus("IDLE");
        return printer;
    }

    private PrinterMonitorProperties monitorProperties() {
        PrinterMonitorProperties properties = new PrinterMonitorProperties();
        properties.setPrinterIds(List.of(403L));
        return properties;
    }

    private PrinterDeviceStatus printingStatus() {
        return new PrinterDeviceStatus(
                PrinterStatus.PRINTING,
                "printing",
                null,
                "demo.gcode",
                BigDecimal.valueOf(35.5),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private PrinterDeviceStatus completedStatus() {
        return new PrinterDeviceStatus(
                PrinterStatus.IDLE,
                "complete",
                null,
                "demo.gcode",
                BigDecimal.valueOf(100),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private PrinterDeviceStatus idleStatus() {
        return new PrinterDeviceStatus(
                PrinterStatus.IDLE,
                "standby",
                null,
                null,
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
