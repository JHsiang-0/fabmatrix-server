package com.example.farm.controller;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.protocol.PrinterProtocolDetector;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.service.PrinterService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PrinterProtocolDetectionControllerTest {
    @Test void detectsAndRechecksBeforePersistingInsteadOfTrustingClient() {
        var detector = mock(PrinterProtocolDetector.class);
        var service = mock(PrinterService.class);
        var controller = new PrinterProtocolDetectionController(detector, service);
        when(detector.detect("192.168.1.20")).thenReturn(PrinterProtocolType.RRF);
        assertThat(controller.detect(new PrinterProtocolDetectionController.ProbeRequest("192.168.1.20"))
                .getData().firmwareType()).isEqualTo("RRF");
        var dto = new PrinterAddDTO();
        dto.setIpAddress("192.168.1.20");
        dto.setFirmwareType("KLIPPER");
        controller.add(dto);
        assertThat(dto.getFirmwareType()).isEqualTo("RRF");
        verify(service).addPrinter(dto);
        verify(detector, times(2)).detect("192.168.1.20");
    }

    @Test void invalidOrUnavailableAddressNeverPersists() {
        var detector = mock(PrinterProtocolDetector.class);
        var service = mock(PrinterService.class);
        var controller = new PrinterProtocolDetectionController(detector, service);
        var dto = new PrinterAddDTO();
        dto.setIpAddress("192.168.1.21");
        assertThat(controller.detect(new PrinterProtocolDetectionController.ProbeRequest(dto.getIpAddress()))
                .getData().firmwareType()).isNull();
        assertThatThrownBy(() -> controller.add(dto)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.detect(new PrinterProtocolDetectionController.ProbeRequest("127.0.0.1/path")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
    }
}
