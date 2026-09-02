package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
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

    @Test
    void routesPauseThroughAdapterWithoutMoonrakerDependency() {
        Printer printer = printer();
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("KLIPPER")).thenReturn(adapter);

        new PrinterControlServiceImpl(printerService, adapterFactory).pause(403L);

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

        assertThatThrownBy(() -> new PrinterControlServiceImpl(printerService, adapterFactory).emergencyStop(403L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(10001));
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
