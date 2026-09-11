package com.example.farm.service;

import com.example.farm.FarmApplication;
import com.example.farm.common.storage.FileStorage;
import com.example.farm.entity.PrintFile;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 以 H2 MySQL 方言、真实 Mapper 和双连接事务验证跨实例重试锁定读语义。 */
@SpringBootTest(classes = FarmApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ")
class PrintJobRetryConcurrencyIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private HikariDataSource dataSource;
    @Autowired
    private PrintJobService printJobService;

    @MockitoBean
    private PrintFileMapper printFileMapper;
    @MockitoBean
    private PrinterService printerService;
    @MockitoBean
    private FileStorage fileStorage;
    @MockitoBean
    private PrinterProtocolAdapterFactory adapterFactory;
    @MockitoBean
    private WebSocketEventPublisher eventPublisher;
    @MockitoBean
    private AuditLogService auditLogService;

    @BeforeEach
    void createJobTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS farm_print_job");
        jdbcTemplate.execute("""
                CREATE TABLE farm_print_job (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    file_id BIGINT, printer_id BIGINT, user_id BIGINT NOT NULL, operator_id BIGINT,
                    idempotency_key VARCHAR(100), file_name_snapshot VARCHAR(255),
                    printer_name_snapshot VARCHAR(50), machine_number_snapshot VARCHAR(50),
                    creator_username_snapshot VARCHAR(50), operator_username_snapshot VARCHAR(50),
                    retry_of_job_id BIGINT, root_job_id BIGINT, attempt_number INT NOT NULL DEFAULT 1,
                    priority INT DEFAULT 0, status VARCHAR(20) NOT NULL, progress DECIMAL(5,2),
                    started_at TIMESTAMP, completed_at TIMESTAMP, error_reason VARCHAR(255),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_retry_source UNIQUE (retry_of_job_id),
                    CONSTRAINT uk_root_attempt UNIQUE (root_job_id, attempt_number)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO farm_print_job
                    (id, file_id, user_id, status, progress, file_name_snapshot,
                     creator_username_snapshot, root_job_id, attempt_number)
                VALUES (100, 20, 1, 'FAILED', 75.00, 'historical.gcode', 'creator', 100, 1)
                """);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentTransactionsUseTwoConnectionsAndReplayOneCommittedChild() throws Exception {
        assertThat(jdbcTemplate.execute((Connection connection) -> connection.getTransactionIsolation()))
                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        PrintFile sourceFile = new PrintFile();
        sourceFile.setId(20L);
        sourceFile.setUserId(1L);
        sourceFile.setIsFolder(false);
        sourceFile.setVisibility("PRIVATE");

        CountDownLatch firstTransactionReachedFileValidation = new CountDownLatch(1);
        CountDownLatch allowFirstTransactionToInsert = new CountDownLatch(1);
        AtomicBoolean firstLookup = new AtomicBoolean(true);
        when(printFileMapper.selectById(20L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (firstLookup.compareAndSet(true, false)) {
                firstTransactionReachedFileValidation.countDown();
                assertThat(allowFirstTransactionToInsert.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return sourceFile;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(() -> retryAsOperator(100L));
            assertThat(firstTransactionReachedFileValidation.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Long> second = executor.submit(() -> retryAsOperator(100L));

            boolean observedTwoConnections = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (dataSource.getHikariPoolMXBean().getActiveConnections() >= 2) {
                    observedTwoConnections = true;
                    break;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertThat(observedTwoConnections).isTrue();
            allowFirstTransactionToInsert.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(101L);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(101L);
        } finally {
            allowFirstTransactionToInsert.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM farm_print_job WHERE retry_of_job_id = 100", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM farm_print_job WHERE id = 100", String.class)).isEqualTo("FAILED");
        verify(eventPublisher, times(1)).publishJobStatus(any());
        verify(auditLogService, times(1)).recordSuccess(
                eq("JOB_RETRY_CREATE"), eq("JOB"), eq(101L), any(String.class));
        verify(auditLogService, times(1)).recordSuccess(
                eq("JOB_RETRY_REPLAY"), eq("JOB"), eq(101L), any(String.class));
    }

    private Long retryAsOperator(Long jobId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, "operator",
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        try {
            return printJobService.retryJob(jobId);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
