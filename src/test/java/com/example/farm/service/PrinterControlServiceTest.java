package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrintJob;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.service.impl.PrinterControlServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PrinterControlServiceTest {

    @Mock
    private PrinterService printerService;
    @Mock
    private PrinterProtocolAdapterFactory adapterFactory;
    @Mock
    private PrinterProtocolAdapter adapter;
    @Mock
    private PrintJobService printJobService;
    @Mock
    private WebSocketEventPublisher eventPublisher;

    @Test
    void routesPauseThroughAdapterWithoutMoonrakerDependency() {
        Printer printer = printer();
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("KLIPPER")).thenReturn(adapter);

        new PrinterControlServiceImpl(printerService, adapterFactory, printJobService, eventPublisher).pause(403L);

        ArgumentCaptor<PrinterEndpoint> endpoint = ArgumentCaptor.forClass(PrinterEndpoint.class);
        verify(adapter).pause(endpoint.capture());
        assertThat(endpoint.getValue().printerId()).isEqualTo(403L);
        assertThat(endpoint.getValue().protocolType().name()).isEqualTo("KLIPPER");
    }

    @Test
    void rejectsOfflinePrinterBeforeCallingAdapter() {
        Printer printer = printer();
        printer.setStatus("OFFLINE");
        when(printerService.getById(403L)).thenReturn(printer);

        assertThatThrownBy(() -> new PrinterControlServiceImpl(printerService, adapterFactory, printJobService, eventPublisher).emergencyStop(403L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(10001));
    }

    @Test
    void resumesPausedCurrentJobThroughAdapterAndPublishesState() {
        Printer printer = printer();
        printer.setStatus("PAUSED");
        printer.setCurrentJobId(1001L);
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setPrinterId(403L);
        job.setStatus("PAUSED");
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("KLIPPER")).thenReturn(adapter);
        when(printJobService.getById(1001L)).thenReturn(job);
        when(printJobService.updateById(job)).thenReturn(true);
        when(printerService.updateById(printer)).thenReturn(true);

        new PrinterControlServiceImpl(printerService, adapterFactory, printJobService, eventPublisher).resume(403L);

        verify(adapter).resume(org.mockito.ArgumentMatchers.any(PrinterEndpoint.class));
        assertThat(job.getStatus()).isEqualTo("PRINTING");
        verify(eventPublisher).publishJobStatus(job);
        assertThat(printer.getStatus()).isEqualTo("PRINTING");
    }

    @Test
    void doesNotPublishResumeEventWhenPrinterStateCannotBeSaved() {
        Printer printer = printer();
        printer.setCurrentJobId(1001L);
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setPrinterId(403L);
        job.setStatus("PAUSED");
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("KLIPPER")).thenReturn(adapter);
        when(printJobService.getById(1001L)).thenReturn(job);
        when(printJobService.updateById(job)).thenReturn(true);
        when(printerService.updateById(printer)).thenReturn(false);

        assertThatThrownBy(() -> new PrinterControlServiceImpl(
                printerService, adapterFactory, printJobService, eventPublisher).resume(403L))
                .hasMessage("恢复打印后更新打印机状态失败");

        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.never()).publishJobStatus(job);
    }

    @Test
    void rejectsResumeWhenPrinterHasNoCurrentJob() {
        Printer printer = printer();
        when(printerService.getById(403L)).thenReturn(printer);

        assertThatThrownBy(() -> new PrinterControlServiceImpl(
                printerService, adapterFactory, printJobService, eventPublisher).resume(403L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("打印机当前没有绑定任务");
    }

    @Test
    void delegatesCancelCurrentJobToJobService() {
        Printer printer = printer();
        printer.setCurrentJobId(1001L);
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setPrinterId(403L);
        job.setStatus("PAUSED");
        when(printerService.getById(403L)).thenReturn(printer);
        when(printJobService.getById(1001L)).thenReturn(job);

        new PrinterControlServiceImpl(printerService, adapterFactory, printJobService, eventPublisher)
                .cancelCurrentJob(403L);

        org.mockito.Mockito.verify(printJobService).cancelJob(1001L);
    }

    private Printer printer() {
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setIpAddress("192.168.1.80");
        printer.setFirmwareType("Klipper");
        printer.setStatus("PRINTING");
        printer.setApiKey("must-stay-internal");
        return printer;
    }
}
