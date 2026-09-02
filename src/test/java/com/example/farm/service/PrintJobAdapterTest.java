package com.example.farm.service;

import com.example.farm.common.utils.RustFsClient;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.service.impl.PrintJobServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class PrintJobAdapterTest {

    @Mock
    private PrintJobMapper printJobMapper;
    @Mock
    private PrintFileMapper printFileMapper;
    @Mock
    private PrinterService printerService;
    @Mock
    private RustFsClient rustFsClient;
    @Mock
    private PrinterProtocolAdapterFactory adapterFactory;
    @Mock
    private PrinterProtocolAdapter adapter;
    @Mock
    private WebSocketEventPublisher eventPublisher;
    @Mock
    private InputStreamResource resource;

    @InjectMocks
    private PrintJobServiceImpl printJobService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void startPrintUploadsThroughSelectedAdapter() {
        mockUser(2L, "OPERATOR");
        PrintJob job = job("ASSIGNED");
        Printer printer = printer();
        PrintFile file = file();
        when(printJobMapper.selectById(1001L)).thenReturn(job);
        when(printerService.getById(403L)).thenReturn(printer);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(rustFsClient.getFileStream("safe-demo.gcode")).thenReturn(resource);
        when(adapterFactory.getAdapter("Klipper")).thenReturn(adapter);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);

        printJobService.startPrint(1001L, 2L, "START_PRINT");

        ArgumentCaptor<PrinterEndpoint> endpoint = ArgumentCaptor.forClass(PrinterEndpoint.class);
        verify(adapter).uploadFile(endpoint.capture(), org.mockito.ArgumentMatchers.same(resource),
                org.mockito.ArgumentMatchers.eq("demo.gcode"), org.mockito.ArgumentMatchers.eq(true));
        assertThat(endpoint.getValue().printerId()).isEqualTo(403L);
        assertThat(endpoint.getValue().apiKey()).isEqualTo("device-secret");
        assertThat(job.getStatus()).isEqualTo("PRINTING");
        verify(eventPublisher).publishJobStatus(job);
    }

    @Test
    void cancelUsesAdapterBeforeUnbindingPrinter() {
        mockUser(2L, "OPERATOR");
        PrintJob job = job("ASSIGNED");
        Printer printer = printer();
        when(printJobMapper.selectById(1001L)).thenReturn(job);
        when(printerService.getById(403L)).thenReturn(printer);
        when(adapterFactory.getAdapter("Klipper")).thenReturn(adapter);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);

        printJobService.cancelJob(1001L);

        verify(adapter).cancel(org.mockito.ArgumentMatchers.any(PrinterEndpoint.class));
        assertThat(job.getStatus()).isEqualTo("CANCELLED");
        assertThat(printer.getCurrentJobId()).isNull();
        verify(eventPublisher).publishJobStatus(job);
    }

    private PrintJob job(String status) {
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setUserId(2L);
        job.setFileId(20L);
        job.setPrinterId(403L);
        job.setStatus(status);
        return job;
    }

    private Printer printer() {
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setIpAddress("192.168.1.80");
        printer.setFirmwareType("Klipper");
        printer.setStatus("PRINTING");
        printer.setIsSafeToPrint(true);
        printer.setApiKey("device-secret");
        printer.setCurrentJobId(1001L);
        return printer;
    }

    private PrintFile file() {
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setOriginalName("demo.gcode");
        file.setSafeName("safe-demo.gcode");
        return file;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
