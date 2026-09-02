package com.example.farm.service;

import com.example.farm.common.utils.RustFsClient;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrintJobCreateDTO;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.service.impl.PrintJobServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class PrintJobCreateTest {

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
    private WebSocketEventPublisher eventPublisher;

    @InjectMocks
    private PrintJobServiceImpl printJobService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsQueuedJobWhenPrinterIsNotSpecified() {
        mockUser(1L, "OPERATOR");
        when(printFileMapper.selectById(20L)).thenReturn(file(20L, 1L));
        AtomicReference<PrintJob> created = stubInsert(false);

        PrintJobCreateDTO request = request(null);
        Long jobId = printJobService.createJob(request);

        assertThat(jobId).isEqualTo(1001L);
        assertThat(created.get().getStatus()).isEqualTo("QUEUED");
        assertThat(created.get().getPrinterId()).isNull();
        verify(printerService, never()).getById(any());
    }

    @Test
    void rejectsStandardCreationWhenTaskInsertFails() {
        mockUser(1L, "OPERATOR");
        when(printFileMapper.selectById(20L)).thenReturn(file(20L, 1L));
        when(printJobMapper.insert(any(PrintJob.class))).thenReturn(0);

        assertThatThrownBy(() -> printJobService.createJob(request(null)))
                .hasMessage("创建打印任务失败：任务记录保存失败");

        verify(printerService, never()).getById(any());
        verify(eventPublisher, never()).publishJobStatus(any());
    }

    @Test
    void rejectsCompatibilitySubmissionWhenTaskInsertFails() {
        when(printFileMapper.selectById(20L)).thenReturn(file(20L, 1L));
        when(printJobMapper.insert(any(PrintJob.class))).thenReturn(0);

        assertThatThrownBy(() -> printJobService.submitJob(20L, 1L, 0))
                .hasMessage("提交打印任务失败：任务记录保存失败");
    }

    @Test
    void specifiedIdlePrinterReceivesAssignedJobWithoutStartingIt() {
        mockUser(1L, "OPERATOR");
        when(printFileMapper.selectById(20L)).thenReturn(file(20L, 1L));
        AtomicReference<PrintJob> created = stubInsert(true);
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setName("Printer-403");
        printer.setStatus("IDLE");
        when(printerService.getById(403L)).thenReturn(printer);
        when(printerService.updateById(any(Printer.class))).thenReturn(true);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);

        Long jobId = printJobService.createJob(request(403L));

        assertThat(jobId).isEqualTo(1001L);
        assertThat(created.get().getStatus()).isEqualTo("ASSIGNED");
        assertThat(created.get().getPrinterId()).isEqualTo(403L);
        assertThat(printer.getCurrentJobId()).isEqualTo(1001L);
        assertThat(printer.getIsSafeToPrint()).isFalse();
        verify(adapterFactory, never()).getAdapter(any());
    }

    @Test
    void manualAssignmentDoesNotPublishWhenPrinterStateCannotBeSaved() {
        mockUser(1L, "OPERATOR");
        when(printFileMapper.selectById(20L)).thenReturn(file(20L, 1L));
        AtomicReference<PrintJob> created = stubInsert(true);
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setName("Printer-403");
        printer.setStatus("IDLE");
        when(printerService.getById(403L)).thenReturn(printer);
        when(printerService.updateById(any(Printer.class))).thenReturn(false);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);

        assertThatThrownBy(() -> printJobService.createJob(request(403L)))
                .hasMessage("派发任务失败：打印机状态保存失败");

        assertThat(created.get().getStatus()).isEqualTo("ASSIGNED");
        verify(eventPublisher, never()).publishJobStatus(any());
    }

    @Test
    void schedulerAssignmentPersistsJobAndPrinterBeforePublishingEvent() {
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setStatus("QUEUED");
        job.setFileId(20L);
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setName("Printer-403");
        printer.setStatus("IDLE");
        when(printJobMapper.selectById(1001L)).thenReturn(job);
        when(printerService.getById(403L)).thenReturn(printer);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);
        when(printerService.updateById(any(Printer.class))).thenReturn(true);

        assertThat(printJobService.assignQueuedJob(1001L, 403L)).isTrue();

        assertThat(job.getStatus()).isEqualTo("ASSIGNED");
        assertThat(job.getPrinterId()).isEqualTo(403L);
        assertThat(printer.getStatus()).isEqualTo("PREPARING");
        assertThat(printer.getCurrentJobId()).isEqualTo(1001L);
        assertThat(printer.getIsSafeToPrint()).isFalse();
        var order = inOrder(printJobMapper, printerService, eventPublisher);
        order.verify(printJobMapper).updateById(job);
        order.verify(printerService).updateById(printer);
        order.verify(eventPublisher).publishJobStatus(job);
    }

    @Test
    void schedulerAssignmentRejectsPrinterWithStaleCurrentJob() {
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setStatus("QUEUED");
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setStatus("IDLE");
        printer.setCurrentJobId(999L);
        when(printJobMapper.selectById(1001L)).thenReturn(job);
        when(printerService.getById(403L)).thenReturn(printer);

        assertThat(printJobService.assignQueuedJob(1001L, 403L)).isFalse();

        verify(printJobMapper, never()).updateById(any(PrintJob.class));
        verify(printerService, never()).updateById(any(Printer.class));
        verify(eventPublisher, never()).publishJobStatus(any());
    }

    @Test
    void schedulerAssignmentFailsWithoutPublishingWhenPrinterUpdateFails() {
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setStatus("QUEUED");
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setStatus("IDLE");
        when(printJobMapper.selectById(1001L)).thenReturn(job);
        when(printerService.getById(403L)).thenReturn(printer);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);
        when(printerService.updateById(any(Printer.class))).thenReturn(false);

        assertThatThrownBy(() -> printJobService.assignQueuedJob(1001L, 403L))
                .hasMessage("自动派发任务失败：打印机状态保存失败");

        verify(eventPublisher, never()).publishJobStatus(any());
    }

    @Test
    void manualAssignmentRejectsIdlePrinterWithExistingBinding() {
        mockUser(1L, "OPERATOR");
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setUserId(1L);
        job.setStatus("QUEUED");
        when(printJobMapper.selectById(1001L)).thenReturn(job);

        Printer printer = new Printer();
        printer.setId(403L);
        printer.setStatus("IDLE");
        printer.setCurrentJobId(999L);
        when(printerService.getById(403L)).thenReturn(printer);

        assertThatThrownBy(() -> printJobService.assignJob(1001L, 403L))
                .hasMessage("打印机当前已绑定任务，无法派发")
                .extracting("code")
                .isEqualTo(409L);

        verify(printJobMapper, never()).updateById(any(PrintJob.class));
        verify(printerService, never()).updateById(any(Printer.class));
        verify(eventPublisher, never()).publishJobStatus(any());
    }

    private AtomicReference<PrintJob> stubInsert(boolean needsReload) {
        AtomicReference<PrintJob> created = new AtomicReference<>();
        doAnswer(invocation -> {
            PrintJob job = invocation.getArgument(0);
            job.setId(1001L);
            created.set(job);
            return 1;
        }).when(printJobMapper).insert(any(PrintJob.class));
        if (needsReload) {
            when(printJobMapper.selectById(1001L)).thenAnswer(invocation -> created.get());
        }
        return created;
    }

    private PrintJobCreateDTO request(Long printerId) {
        PrintJobCreateDTO request = new PrintJobCreateDTO();
        request.setFileId(20L);
        request.setPriority(10);
        request.setPrinterId(printerId);
        return request;
    }

    private PrintFile file(Long id, Long userId) {
        PrintFile file = new PrintFile();
        file.setId(id);
        file.setUserId(userId);
        file.setIsFolder(false);
        return file;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
