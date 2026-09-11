package com.example.farm.service;

import com.example.farm.entity.PrintJob;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.request.FileJobsQueryDTO;
import com.example.farm.entity.dto.request.PrintJobQueryDTO;
import com.example.farm.entity.dto.request.UpdatePrintJobPriorityRequest;
import com.example.farm.entity.vo.PrintJobDetailVO;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.entity.enums.PrintJobTimeField;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.service.impl.PrintJobServiceImpl;
import com.example.farm.service.PrinterService;
import com.example.farm.common.utils.MoonrakerApiClient;
import com.example.farm.common.utils.RustFsClient;
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
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintJobOwnershipTest {

    @Mock
    private PrintJobMapper printJobMapper;

    @Mock
    private PrintFileMapper printFileMapper;

    @Mock
    private PrinterService printerService;

    @Mock
    private RustFsClient rustFsClient;

    @Mock
    private MoonrakerApiClient moonrakerApiClient;

    @Mock
    private WebSocketEventPublisher eventPublisher;

    @InjectMocks
    private PrintJobServiceImpl printJobService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void operatorCannotReadAnotherUsersJob() {
        mockUser(2L, "OPERATOR");
        when(printJobMapper.selectById(100L)).thenReturn(job(100L, 1L));

        assertThatThrownBy(() -> printJobService.getAccessibleJob(100L))
                .hasMessage("任务不存在");
    }

    @Test
    void rejectsMissingJobQuery() {
        assertThatThrownBy(() -> printJobService.queryJobs(null))
                .hasMessage("任务查询参数不能为空")
                .extracting("code")
                .isEqualTo(400L);
    }

    @Test
    void rejectsReversedJobQueryTimeRange() {
        PrintJobQueryDTO query = new PrintJobQueryDTO();
        query.setStartTime(LocalDateTime.of(2026, 9, 3, 12, 0));
        query.setEndTime(LocalDateTime.of(2026, 9, 3, 11, 0));

        assertThatThrownBy(() -> printJobService.queryJobs(query))
                .hasMessage("开始时间不能晚于结束时间")
                .extracting("code")
                .isEqualTo(400L);
    }

    @Test
    void rejectsMixedOrUnknownJobQueryStatuses() {
        mockUser(1L, "OPERATOR");

        PrintJobQueryDTO mixed = new PrintJobQueryDTO();
        mixed.setStatus("COMPLETED");
        mixed.setStatuses(List.of("FAILED"));
        assertThatThrownBy(() -> printJobService.queryJobs(mixed))
                .hasMessage("status 与 statuses 不能同时使用")
                .extracting("code")
                .isEqualTo(400L);

        PrintJobQueryDTO unknown = new PrintJobQueryDTO();
        unknown.setStatuses(List.of("NOT_A_STATUS"));
        assertThatThrownBy(() -> printJobService.queryJobs(unknown))
                .hasMessageContaining("未知任务状态")
                .extracting("code")
                .isEqualTo(400L);

        verifyNoInteractions(printJobMapper);
    }

    @Test
    void rejectsExplicitEmptyStatusesEvenWhenSingleStatusIsAbsentOrPresent() {
        mockUser(1L, "OPERATOR");

        PrintJobQueryDTO empty = new PrintJobQueryDTO();
        empty.setStatuses(List.of());
        assertThatThrownBy(() -> printJobService.queryJobs(empty))
                .hasMessage("statuses 不能为空")
                .extracting("code")
                .isEqualTo(400L);

        PrintJobQueryDTO mixedWithEmpty = new PrintJobQueryDTO();
        mixedWithEmpty.setStatus("COMPLETED");
        mixedWithEmpty.setStatuses(List.of());
        assertThatThrownBy(() -> printJobService.queryJobs(mixedWithEmpty))
                .hasMessage("status 与 statuses 不能同时使用")
                .extracting("code")
                .isEqualTo(400L);

        verifyNoInteractions(printJobMapper);
    }

    @Test
    void trimsFileNameBeforeApplyingThe255CharacterLimit() {
        mockUser(1L, "OPERATOR");
        PrintJobQueryDTO query = new PrintJobQueryDTO();
        query.setFileName(" ".repeat(255) + "model.gcode");
        Page<PrintJobSummaryVO> page = new Page<>(1, 10);
        when(printJobMapper.selectPageSummaries(any(Page.class), eq(List.of()), eq(null), eq(null),
                eq(1L), eq(false), eq("model.gcode"), eq(PrintJobTimeField.CREATED_AT), isNull(), isNull()))
                .thenReturn(page);

        assertThat(printJobService.queryJobs(query)).isSameAs(page);
        verify(printJobMapper).selectPageSummaries(any(Page.class), eq(List.of()), eq(null), eq(null),
                eq(1L), eq(false), eq("model.gcode"), eq(PrintJobTimeField.CREATED_AT), isNull(), isNull());

        PrintJobQueryDTO tooLong = new PrintJobQueryDTO();
        tooLong.setFileName("x".repeat(256));
        assertThatThrownBy(() -> printJobService.queryJobs(tooLong))
                .hasMessage("文件名筛选最多255个字符")
                .extracting("code")
                .isEqualTo(400L);
    }

    @Test
    void queryNormalizesLegacyStatusAndKeepsGetPathReadOnly() {
        mockUser(1L, "OPERATOR");
        PrintJobQueryDTO query = new PrintJobQueryDTO();
        query.setStatuses(List.of("PENDING", "CANCELED"));
        Page<PrintJobSummaryVO> page = new Page<>(1, 10);
        when(printJobMapper.selectPageSummaries(any(Page.class), eq(List.of(
                "QUEUED", "PENDING", "MANUAL", "CANCELLED", "CANCELED")), eq(null), eq(null),
                eq(1L), eq(false), isNull(), eq(PrintJobTimeField.CREATED_AT), isNull(), isNull()))
                .thenReturn(page);

        assertThat(printJobService.queryJobs(query)).isSameAs(page);
        verify(printJobMapper).selectPageSummaries(any(Page.class), eq(List.of(
                "QUEUED", "PENDING", "MANUAL", "CANCELLED", "CANCELED")), eq(null), eq(null),
                eq(1L), eq(false), isNull(), eq(PrintJobTimeField.CREATED_AT), isNull(), isNull());
        verify(printJobMapper, never()).updateById(any(PrintJob.class));
    }

    @Test
    void operatorCanReadOwnJob() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        when(printJobMapper.selectById(100L)).thenReturn(job);

        assertThat(printJobService.getAccessibleJob(100L)).isSameAs(job);
    }

    @Test
    void adminCanReadAnyJob() {
        mockUser(2L, "ADMIN");
        PrintJob job = job(100L, 1L);
        when(printJobMapper.selectById(100L)).thenReturn(job);

        assertThat(printJobService.getAccessibleJob(100L)).isSameAs(job);
    }

    @Test
    void operatorCannotReadAnotherUsersJobDetail() {
        mockUser(2L, "OPERATOR");
        when(printJobMapper.selectAccessibleDetail(100L, 2L, false)).thenReturn(null);

        assertThatThrownBy(() -> printJobService.getAccessibleJobDetail(100L))
                .hasMessage("任务不存在")
                .extracting("code")
                .isEqualTo(404L);
    }

    @Test
    void missingJobDetailUsesSameNotFoundBoundary() {
        mockUser(2L, "OPERATOR");
        when(printJobMapper.selectAccessibleDetail(404L, 2L, false)).thenReturn(null);

        assertThatThrownBy(() -> printJobService.getAccessibleJobDetail(404L))
                .hasMessage("任务不存在")
                .extracting("code")
                .isEqualTo(404L);
    }

    @Test
    void queueSummaryQueryIsDelegatedWithSqlOwnershipBoundary() {
        mockUser(2L, "OPERATOR");
        List<PrintJobSummaryVO> summaries = List.of(new PrintJobSummaryVO());
        when(printJobMapper.selectQueuedSummaries(2L, false)).thenReturn(summaries);

        assertThat(printJobService.getQueuedJobsForCurrentUser()).isSameAs(summaries);
        verify(printJobMapper).selectQueuedSummaries(2L, false);
        verify(printJobMapper, never()).selectById(any());
        verify(printJobMapper, never()).updateById(any(PrintJob.class));
        verify(eventPublisher, never()).publishJobStatus(any());
    }

    @Test
    void pageSummaryQueryKeepsExistingFiltersAndDelegatesCurrentUserToSql() {
        mockUser(2L, "OPERATOR");
        PrintJobQueryDTO query = new PrintJobQueryDTO();
        query.setStatus("COMPLETED");
        query.setPrinterId(403L);
        query.setUserId(1L);
        Page<PrintJobSummaryVO> page = new Page<>(1, 10);
        when(printJobMapper.selectPageSummaries(any(Page.class), anyList(), eq(403L), eq(1L),
                eq(2L), eq(false), isNull(), eq(PrintJobTimeField.CREATED_AT), isNull(), isNull()))
                .thenReturn(page);

        assertThat(printJobService.queryJobs(query)).isSameAs(page);
        verify(printJobMapper).selectPageSummaries(any(Page.class), eq(List.of("COMPLETED")), eq(403L), eq(1L),
                eq(2L), eq(false), isNull(), eq(PrintJobTimeField.CREATED_AT), isNull(), isNull());
    }

    @Test
    void operatorCanQueryOnlyOwnFileJobs() {
        mockUser(1L, "OPERATOR");
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setUserId(1L);
        Page<PrintJob> page = new Page<>(1, 10);
        when(printFileMapper.selectById(20L)).thenReturn(file);
        when(printJobMapper.selectPageByFileId(any(Page.class), eq(20L), eq(1L), eq(false)))
                .thenReturn(page);

        Page<PrintJob> actual = printJobService.queryJobsByFileId(20L, new FileJobsQueryDTO());

        assertThat(actual).isSameAs(page);
        verify(printJobMapper).selectPageByFileId(any(Page.class), eq(20L), eq(1L), eq(false));
    }

    @Test
    void operatorCannotQueryAnotherUsersFileJobs() {
        mockUser(2L, "OPERATOR");
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setUserId(1L);
        when(printFileMapper.selectById(20L)).thenReturn(file);

        assertThatThrownBy(() -> printJobService.queryJobsByFileId(20L, new FileJobsQueryDTO()))
                .hasMessage("文件不存在");
        verify(printJobMapper, never()).selectPageByFileId(any(Page.class), eq(20L), eq(2L), eq(false));
    }

    @Test
    void retriesOwnFailedJobAndClearsRuntimeFields() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        job.setFileId(20L);
        job.setPrinterId(403L);
        job.setOperatorId(8L);
        job.setStatus("FAILED");
        job.setProgress(new java.math.BigDecimal("42.50"));
        job.setStartedAt(java.time.LocalDateTime.now().minusMinutes(5));
        job.setCompletedAt(java.time.LocalDateTime.now());
        job.setErrorReason("设备异常");
        when(printJobMapper.selectById(100L)).thenReturn(job);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);

        printJobService.retryJob(100L);

        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getProgress()).isEqualByComparingTo("0");
        assertThat(job.getPrinterId()).isNull();
        assertThat(job.getOperatorId()).isNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getCompletedAt()).isNull();
        assertThat(job.getErrorReason()).isNull();
        verify(eventPublisher).publishJobStatus(job);
    }

    @Test
    void cannotRetryCompletedJob() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        job.setStatus("COMPLETED");
        when(printJobMapper.selectById(100L)).thenReturn(job);

        assertThatThrownBy(() -> printJobService.retryJob(100L))
                .hasMessage("任务状态不允许从 [COMPLETED] 转换为 [QUEUED]");
        verify(printJobMapper, never()).updateById(any(PrintJob.class));
    }

    @Test
    void requeuesAssignedJobAndReleasesPreparingPrinter() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        job.setPrinterId(403L);
        job.setStatus("ASSIGNED");
        when(printJobMapper.selectById(100L)).thenReturn(job);
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setCurrentJobId(100L);
        printer.setStatus("PREPARING");
        printer.setIsSafeToPrint(true);
        when(printerService.getById(403L)).thenReturn(printer);
        when(printerService.clearJobBinding(403L, 100L)).thenReturn(true);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);

        printJobService.requeueJob(100L);

        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getPrinterId()).isNull();
        assertThat(job.getProgress()).isEqualByComparingTo("0");
        assertThat(printer.getCurrentJobId()).isNull();
        assertThat(printer.getStatus()).isEqualTo("IDLE");
        assertThat(printer.getIsSafeToPrint()).isFalse();
        verify(eventPublisher).publishJobStatus(job);
    }

    @Test
    void requeueFailsWithoutPublishingWhenPrinterCannotBeReleased() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        job.setPrinterId(403L);
        job.setStatus("ASSIGNED");
        when(printJobMapper.selectById(100L)).thenReturn(job);
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setCurrentJobId(100L);
        printer.setStatus("PREPARING");
        when(printerService.getById(403L)).thenReturn(printer);
        when(printerService.clearJobBinding(403L, 100L)).thenReturn(false);

        assertThatThrownBy(() -> printJobService.requeueJob(100L))
                .hasMessage("重新排队任务失败：打印机状态保存失败");

        verify(printJobMapper, never()).updateById(any(PrintJob.class));
        verify(eventPublisher, never()).publishJobStatus(any());
    }

    @Test
    void pausedJobCannotBeRequeuedWithoutExplicitCancel() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        job.setPrinterId(403L);
        job.setStatus("PAUSED");
        when(printJobMapper.selectById(100L)).thenReturn(job);

        assertThatThrownBy(() -> printJobService.requeueJob(100L))
                .hasMessage("只有已派发或已就绪任务可以重新排队");
        verify(printerService, never()).getById(403L);
        verify(printJobMapper, never()).updateById(any(PrintJob.class));
    }

    @Test
    void updatesPriorityOfOwnQueuedJob() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        job.setStatus("QUEUED");
        job.setPriority(1);
        when(printJobMapper.selectById(100L)).thenReturn(job);
        when(printJobMapper.updateById(any(PrintJob.class))).thenReturn(1);
        UpdatePrintJobPriorityRequest request = new UpdatePrintJobPriorityRequest();
        request.setPriority(80);

        printJobService.updatePriority(100L, request);

        assertThat(job.getPriority()).isEqualTo(80);
        verify(printJobMapper).updateById(job);
    }

    @Test
    void cannotUpdatePriorityAfterTaskIsAssigned() {
        mockUser(1L, "OPERATOR");
        PrintJob job = job(100L, 1L);
        job.setStatus("ASSIGNED");
        when(printJobMapper.selectById(100L)).thenReturn(job);
        UpdatePrintJobPriorityRequest request = new UpdatePrintJobPriorityRequest();
        request.setPriority(80);

        assertThatThrownBy(() -> printJobService.updatePriority(100L, request))
                .hasMessage("只有排队中的任务可以修改优先级");
        verify(printJobMapper, never()).updateById(any(PrintJob.class));
    }

    private PrintJob job(Long id, Long userId) {
        PrintJob job = new PrintJob();
        job.setId(id);
        job.setUserId(userId);
        return job;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
