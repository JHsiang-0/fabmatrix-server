package com.example.farm.service;

import com.example.farm.common.utils.RustFsClient;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.service.impl.PrintJobServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrintJobRetryAuditTest {

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
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PrintJobServiceImpl printJobService;

    @BeforeEach
    void injectOptionalAuditService() {
        ReflectionTestUtils.setField(printJobService, "auditLogService", auditLogService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsImmutableChildWithOnlyHistoricalCreationSnapshots() {
        mockUser(1L, "OPERATOR");
        PrintJob source = failedSource();
        when(printJobMapper.selectById(100L)).thenReturn(source);
        when(printJobMapper.selectRetryChild(100L)).thenReturn(null);
        when(printFileMapper.selectById(20L)).thenReturn(file());
        AtomicReference<PrintJob> childRef = new AtomicReference<>();
        when(printJobMapper.insertRetryIfAbsent(any(PrintJob.class))).thenAnswer(invocation -> {
            PrintJob child = invocation.getArgument(0);
            child.setId(101L);
            childRef.set(child);
            return 1;
        });

        Long childId = printJobService.retryJob(100L);

        assertThat(childId).isEqualTo(101L);
        PrintJob child = childRef.get();
        assertThat(child.getStatus()).isEqualTo("QUEUED");
        assertThat(child.getProgress()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(child.getUserId()).isEqualTo(1L);
        assertThat(child.getFileId()).isEqualTo(20L);
        assertThat(child.getPriority()).isEqualTo(70);
        assertThat(child.getFileNameSnapshot()).isEqualTo("old-name.gcode");
        assertThat(child.getCreatorUsernameSnapshot()).isEqualTo("old-creator");
        assertThat(child.getRetryOfJobId()).isEqualTo(100L);
        assertThat(child.getRootJobId()).isEqualTo(88L);
        assertThat(child.getAttemptNumber()).isEqualTo(3);
        assertThat(child.getPrinterId()).isNull();
        assertThat(child.getPrinterNameSnapshot()).isNull();
        assertThat(child.getMachineNumberSnapshot()).isNull();
        assertThat(child.getOperatorId()).isNull();
        assertThat(child.getOperatorUsernameSnapshot()).isNull();
        assertThat(child.getStartedAt()).isNull();
        assertThat(child.getCompletedAt()).isNull();
        assertThat(child.getErrorReason()).isNull();
        assertThat(child.getIdempotencyKey()).isNull();
        assertThat(source.getStatus()).isEqualTo("FAILED");
        assertThat(source.getPrinterId()).isEqualTo(403L);
        assertThat(source.getOperatorId()).isEqualTo(8L);
        assertThat(source.getErrorReason()).isEqualTo("设备异常");
        verify(auditLogService).recordSuccess(eq("JOB_RETRY_CREATE"), eq("JOB"), eq(101L), any(String.class));
        verify(printerService, never()).getById(any());
        verify(rustFsClient, never()).getFileStream(any());
        verify(adapterFactory, never()).getAdapter(any());
    }

    @Test
    void replaysExistingChildWithoutRevalidatingDeletedSourceFile() {
        mockUser(1L, "OPERATOR");
        PrintJob source = failedSource();
        PrintJob child = new PrintJob();
        child.setId(101L);
        child.setUserId(1L);
        child.setFileId(20L);
        child.setRetryOfJobId(100L);
        child.setRootJobId(88L);
        child.setAttemptNumber(3);
        when(printJobMapper.selectById(100L)).thenReturn(source);
        when(printJobMapper.selectRetryChild(100L)).thenReturn(child);

        assertThat(printJobService.retryJob(100L)).isEqualTo(101L);

        verify(printFileMapper, never()).selectById(any());
        verify(printJobMapper, never()).insertRetryIfAbsent(any(PrintJob.class));
        verify(auditLogService).recordSuccess(eq("JOB_RETRY_REPLAY"), eq("JOB"), eq(101L), any(String.class));
    }

    @Test
    void concurrentRetryRequestsReturnTheSameChildAndPublishOnce() throws Exception {
        PrintJob source = failedSource();
        AtomicReference<PrintJob> childRef = new AtomicReference<>();
        when(printJobMapper.selectById(100L)).thenReturn(source);
        when(printJobMapper.selectRetryChild(100L)).thenAnswer(invocation -> {
            synchronized (childRef) {
                return childRef.get();
            }
        });
        when(printFileMapper.selectById(20L)).thenReturn(file());
        when(printJobMapper.insertRetryIfAbsent(any(PrintJob.class))).thenAnswer(invocation -> {
            synchronized (childRef) {
                if (childRef.get() == null) {
                    PrintJob candidate = invocation.getArgument(0);
                    candidate.setId(101L);
                    childRef.set(candidate);
                    return 1;
                }
                return 0;
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(retryAsUser(1L));
            Future<Long> second = executor.submit(retryAsUser(1L));

            assertThat(first.get()).isEqualTo(101L);
            assertThat(second.get()).isEqualTo(101L);
        } finally {
            executor.shutdownNow();
        }

        verify(printJobMapper, atLeast(2)).selectRetryChild(100L);
        verify(printJobMapper, atLeastOnce()).insertRetryIfAbsent(any(PrintJob.class));
        verify(printJobMapper, atMost(2)).insertRetryIfAbsent(any(PrintJob.class));
        verify(eventPublisher, times(1)).publishJobStatus(any(PrintJob.class));
        verify(auditLogService, times(1)).recordSuccess(eq("JOB_RETRY_CREATE"), eq("JOB"), eq(101L), any(String.class));
        verify(auditLogService, times(1)).recordSuccess(eq("JOB_RETRY_REPLAY"), eq("JOB"), eq(101L), any(String.class));
    }

    @Test
    void rejectsConflictingExistingChildWithoutCreatingAnotherLineage() {
        mockUser(1L, "OPERATOR");
        PrintJob source = failedSource();
        PrintJob conflicting = new PrintJob();
        conflicting.setId(999L);
        conflicting.setUserId(1L);
        conflicting.setFileId(20L);
        conflicting.setRetryOfJobId(100L);
        conflicting.setRootJobId(999L);
        conflicting.setAttemptNumber(2);
        when(printJobMapper.selectById(100L)).thenReturn(source);
        when(printJobMapper.selectRetryChild(100L)).thenReturn(conflicting);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> printJobService.retryJob(100L))
                .hasMessage("重试任务链数据冲突")
                .extracting("code").isEqualTo(409L);

        verify(printJobMapper, never()).insertRetryIfAbsent(any(PrintJob.class));
        verify(auditLogService).recordFailure("JOB_RETRY_REJECT", "JOB", 100L,
                "打印任务重试", "RETRY_LINEAGE_CONFLICT");
    }

    @Test
    void auditProxyFailureCannotReplaceStableRetryRejection() {
        mockUser(1L, "OPERATOR");
        PrintJob source = failedSource();
        source.setStatus("READY");
        when(printJobMapper.selectById(100L)).thenReturn(source);
        when(printJobMapper.selectRetryChild(100L)).thenReturn(null);
        doThrow(new IllegalStateException("audit transaction unavailable"))
                .when(auditLogService).recordFailure("JOB_RETRY_REJECT", "JOB", 100L,
                        "打印任务重试", "JOB_NOT_FAILED");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> printJobService.retryJob(100L))
                .hasMessage("只有 FAILED 状态的任务可以重试")
                .extracting("code").isEqualTo(422L);
    }

    private Callable<Long> retryAsUser(Long userId) {
        return () -> {
            mockUser(userId, "OPERATOR");
            try {
                return printJobService.retryJob(100L);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private PrintJob failedSource() {
        PrintJob source = new PrintJob();
        source.setId(100L);
        source.setUserId(1L);
        source.setFileId(20L);
        source.setPrinterId(403L);
        source.setOperatorId(8L);
        source.setPriority(70);
        source.setStatus("FAILED");
        source.setProgress(new BigDecimal("42.50"));
        source.setStartedAt(LocalDateTime.now().minusMinutes(5));
        source.setCompletedAt(LocalDateTime.now());
        source.setErrorReason("设备异常");
        source.setFileNameSnapshot("old-name.gcode");
        source.setCreatorUsernameSnapshot("old-creator");
        source.setRootJobId(88L);
        source.setAttemptNumber(2);
        return source;
    }

    private PrintFile file() {
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setUserId(1L);
        file.setIsFolder(false);
        file.setVisibility("PRIVATE");
        file.setOriginalName("current-name.gcode");
        return file;
    }

    private void mockUser(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
