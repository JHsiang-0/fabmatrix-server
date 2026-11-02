package com.example.farm.task;

import com.example.farm.config.PrinterMonitorProperties;
import com.example.farm.entity.Printer;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.WebSocketEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PrinterMonitorShutdownTest {
    @Test
    void contextStopsMonitorBeforePhaseZeroDependencyAndDoesNotMarkInterruptedDeviceOffline() throws Exception {
        var printers = mock(PrinterService.class);
        var cache = mock(PrinterCacheService.class);
        var adapters = mock(PrinterProtocolAdapterFactory.class);
        var adapter = mock(PrinterProtocolAdapter.class);
        var events = mock(WebSocketEventPublisher.class);
        var properties = new PrinterMonitorProperties();
        properties.setPrinterIds(List.of(123L));
        var printer = new Printer();
        printer.setId(123L);
        printer.setFirmwareType("RRF");
        printer.setIpAddress("192.0.2.1");
        printer.setMonitorEnabled(true);
        when(printers.listByIds(any())).thenReturn(List.of(printer));
        when(printers.getById(123L)).thenReturn(printer);
        when(adapters.getAdapter("RRF")).thenReturn(adapter);
        var entered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        when(adapter.getStatus(any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("request interrupted", exception);
            }
            return null;
        });
        var monitor = new PrinterMonitorTask(printers, cache, adapters, mock(PrintJobService.class), events, properties);
        var dependencyStoppedAfterMonitor = new AtomicBoolean();
        SmartLifecycle dependency = new SmartLifecycle() {
            private boolean running;
            @Override public void start() { running = true; }
            @Override public void stop() {
                dependencyStoppedAfterMonitor.set(!monitor.isRunning() && interrupted.get());
                running = false;
            }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return 0; }
        };
        var context = new GenericApplicationContext();
        context.registerBean("dependency", SmartLifecycle.class, () -> dependency);
        context.registerBean("monitor", PrinterMonitorTask.class, () -> monitor);
        context.refresh();
        try {
            monitor.checkPrinterStatus();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            context.close();
        }
        assertThat(interrupted).isTrue();
        assertThat(dependencyStoppedAfterMonitor).isTrue();
        verify(cache, never()).markPrinterOffline(any());
        verify(events, never()).publishPrinterOffline(any(), any());
        monitor.checkPrinterStatus();
        verify(printers, times(1)).listByIds(any());
    }
}
