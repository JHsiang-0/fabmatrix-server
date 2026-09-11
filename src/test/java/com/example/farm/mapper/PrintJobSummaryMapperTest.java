package com.example.farm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.FarmApplication;
import com.example.farm.entity.vo.PrintJobDetailVO;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.entity.enums.PrintJobTimeField;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** TC-002 查询摘要、权限下推和 LEFT JOIN 集成测试。 */
@SpringBootTest(classes = FarmApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PrintJobSummaryMapperTest.SqlCountConfiguration.class)
class PrintJobSummaryMapperTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PrintJobMapper printJobMapper;

    @Autowired
    private SqlStatementCountInterceptor sqlStatementCountInterceptor;

    @BeforeEach
    void createTables() {
        dropTables();
        jdbcTemplate.execute("""
                CREATE TABLE farm_user (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(50) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE farm_print_file (
                    id BIGINT PRIMARY KEY,
                    original_name VARCHAR(255),
                    material_type VARCHAR(20),
                    nozzle_size DECIMAL(3, 2)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE farm_printer (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(50),
                    machine_number VARCHAR(50)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE farm_print_job (
                    id BIGINT PRIMARY KEY,
                    file_id BIGINT,
                    printer_id BIGINT,
                    user_id BIGINT NOT NULL,
                    operator_id BIGINT,
                    priority INT,
                    status VARCHAR(20) NOT NULL,
                    progress DECIMAL(5, 2),
                    started_at TIMESTAMP,
                    completed_at TIMESTAMP,
                    error_reason VARCHAR(255),
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);

        jdbcTemplate.update("INSERT INTO farm_user (id, username) VALUES (?, ?)", 10L, "creator");
        jdbcTemplate.update("INSERT INTO farm_user (id, username) VALUES (?, ?)", 11L, "other");
        jdbcTemplate.update("INSERT INTO farm_user (id, username) VALUES (?, ?)", 12L, "operator");
        jdbcTemplate.update("INSERT INTO farm_print_file (id, original_name, material_type, nozzle_size) VALUES (?, ?, ?, ?)",
                100L, "model.gcode", "PLA", new BigDecimal("0.40"));
        jdbcTemplate.update("INSERT INTO farm_printer (id, name, machine_number) VALUES (?, ?, ?)",
                200L, "Printer A", "M-01");

        insertJob(20L, 100L, 200L, 10L, 12L, 30, "PRINTING", "2026-09-11 10:00:00");
        insertJob(21L, 999L, 999L, 10L, 999L, 20, "COMPLETED", "2026-09-11 11:00:00");
        insertJob(30L, null, null, 10L, null, 5, "QUEUED", "2026-09-11 09:00:00");
        insertJob(31L, null, null, 10L, null, 5, "QUEUED", "2026-09-11 09:00:00");
        insertJob(32L, null, null, 11L, null, 10, "QUEUED", "2026-09-11 08:00:00");
        insertJob(33L, null, null, 10L, null, 99, "PENDING", "2026-09-11 07:00:00");
        insertJob(34L, null, null, 10L, null, 98, "MANUAL", "2026-09-11 06:00:00");
    }

    @AfterEach
    void dropTablesAfterTest() {
        dropTables();
    }

    @Test
    void mapsAllSevenReadableSummaryFieldsAndKeepsMissingAssociations() {
        List<PrintJobSummaryVO> summaries = printJobMapper.selectQueuedSummaries(10L, false);
        PrintJobSummaryVO missing = printJobMapper.selectPageSummaries(
                        new Page<>(1, 10), "COMPLETED", null, null, 10L, false, null, null)
                .getRecords().stream().findFirst().orElseThrow();
        PrintJobSummaryVO mapped = printJobMapper.selectPageSummaries(
                        new Page<>(1, 10), "PRINTING", null, null, 10L, false, null, null)
                .getRecords().stream().findFirst().orElseThrow();

        assertThat(mapped).extracting(PrintJobSummaryVO::getFileName,
                        PrintJobSummaryVO::getPrinterName, PrintJobSummaryVO::getMachineNumber,
                        PrintJobSummaryVO::getCreatorUsername, PrintJobSummaryVO::getOperatorUsername,
                        PrintJobSummaryVO::getMaterialType, PrintJobSummaryVO::getNozzleSize)
                .containsExactly("model.gcode", "Printer A", "M-01", "creator", "operator",
                        "PLA", new BigDecimal("0.40"));
        assertThat(missing).extracting(PrintJobSummaryVO::getId,
                        PrintJobSummaryVO::getFileName, PrintJobSummaryVO::getPrinterName,
                        PrintJobSummaryVO::getCreatorUsername, PrintJobSummaryVO::getOperatorUsername,
                        PrintJobSummaryVO::getMaterialType, PrintJobSummaryVO::getNozzleSize)
                .containsExactly(21L, null, null, "creator", null, null, null);
        assertThat(summaries).extracting(PrintJobSummaryVO::getId).containsExactly(30L, 31L);
    }

    @Test
    void queueFiltersExactQueuedStateAndUsesStablePriorityAndIdOrder() {
        List<PrintJobSummaryVO> operatorQueue = printJobMapper.selectQueuedSummaries(10L, false);
        assertThat(operatorQueue).extracting(PrintJobSummaryVO::getId).containsExactly(30L, 31L);

        List<PrintJobSummaryVO> adminQueue = printJobMapper.selectQueuedSummaries(null, true);
        assertThat(adminQueue).extracting(PrintJobSummaryVO::getId).containsExactly(32L, 30L, 31L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM farm_print_job WHERE id = 33", String.class)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM farm_print_job WHERE id = 34", String.class)).isEqualTo("MANUAL");
    }

    @Test
    void pageTotalAndRowsAreIsolatedForOperatorAndAdminAndSortByIdDesc() {
        insertJob(40L, null, null, 10L, null, 1, "COMPLETED", "2026-09-11 12:00:00");
        insertJob(41L, null, null, 11L, null, 1, "COMPLETED", "2026-09-11 12:00:00");

        Page<PrintJobSummaryVO> operatorPage = printJobMapper.selectPageSummaries(
                new Page<>(1, 10), "COMPLETED", null, 11L, 10L, false, null, null);
        Page<PrintJobSummaryVO> adminPage = printJobMapper.selectPageSummaries(
                new Page<>(1, 10), "COMPLETED", null, null, 10L, true, null, null);

        assertThat(operatorPage.getTotal()).isEqualTo(2);
        assertThat(operatorPage.getRecords()).extracting(PrintJobSummaryVO::getId).containsExactly(40L, 21L);
        assertThat(adminPage.getTotal()).isEqualTo(3);
        assertThat(adminPage.getRecords()).extracting(PrintJobSummaryVO::getId).containsExactly(41L, 40L, 21L);
    }

    @Test
    void queueUsesOneSqlStatementRegardlessOfReturnedRowCount() {
        sqlStatementCountInterceptor.reset();
        List<PrintJobSummaryVO> smallQueue = printJobMapper.selectQueuedSummaries(10L, false);
        int smallQueueStatements = sqlStatementCountInterceptor.count();

        for (long id = 100L; id < 125L; id++) {
            insertJob(id, null, null, 10L, null, 1, "QUEUED", "2026-09-11 14:00:00");
        }
        sqlStatementCountInterceptor.reset();
        List<PrintJobSummaryVO> largeQueue = printJobMapper.selectQueuedSummaries(10L, false);
        int largeQueueStatements = sqlStatementCountInterceptor.count();

        assertThat(smallQueue).hasSize(2);
        assertThat(largeQueue).hasSize(27);
        assertThat(smallQueueStatements).isEqualTo(1);
        assertThat(largeQueueStatements).isEqualTo(1);
    }

    @Test
    void pageUsesCountAndDataStatementsRegardlessOfReturnedRowCount() {
        sqlStatementCountInterceptor.reset();
        Page<PrintJobSummaryVO> smallPage = printJobMapper.selectPageSummaries(
                new Page<>(1, 100), "COMPLETED", null, null, 10L, false, null, null);
        int smallPageStatements = sqlStatementCountInterceptor.count();

        for (long id = 200L; id < 225L; id++) {
            insertJob(id, null, null, 10L, null, 1, "COMPLETED", "2026-09-11 14:00:00");
        }
        sqlStatementCountInterceptor.reset();
        Page<PrintJobSummaryVO> largePage = printJobMapper.selectPageSummaries(
                new Page<>(1, 100), "COMPLETED", null, null, 10L, false, null, null);
        int largePageStatements = sqlStatementCountInterceptor.count();

        assertThat(smallPage.getTotal()).isEqualTo(1);
        assertThat(largePage.getTotal()).isEqualTo(26);
        assertThat(smallPageStatements).isEqualTo(2);
        assertThat(largePageStatements).isEqualTo(2);
    }

    @Test
    void pageRetainsPrinterUserAndCreatedAtFilters() {
        insertJob(42L, null, 200L, 10L, null, 1, "COMPLETED", "2026-09-11 13:00:00");

        Page<PrintJobSummaryVO> page = printJobMapper.selectPageSummaries(
                new Page<>(1, 10), "COMPLETED", 200L, 10L, 99L, true,
                LocalDateTime.of(2026, 9, 11, 12, 30),
                LocalDateTime.of(2026, 9, 11, 13, 30));

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).extracting(PrintJobSummaryVO::getId).containsExactly(42L);
    }

    @Test
    void activeStatusesUseOneServerPageAndReachTheSecondPage() {
        for (long id = 1000L; id < 1110L; id++) {
            insertJob(id, null, null, 10L, null, 1,
                    id % 2 == 0 ? "ASSIGNED" : "PRINTING", "2026-09-12 00:00:00");
        }

        Page<PrintJobSummaryVO> firstPage = printJobMapper.selectPageSummaries(
                new Page<>(1, 100),
                List.of("ASSIGNED", "PRINTING", "READY", "UPLOADING", "PAUSED", "RECONCILING"),
                null, null, 10L, false, null, PrintJobTimeField.CREATED_AT, null, null);
        Page<PrintJobSummaryVO> secondPage = printJobMapper.selectPageSummaries(
                new Page<>(2, 100),
                List.of("ASSIGNED", "PRINTING", "READY", "UPLOADING", "PAUSED", "RECONCILING"),
                null, null, 10L, false, null, PrintJobTimeField.CREATED_AT, null, null);

        assertThat(firstPage.getTotal()).isEqualTo(111);
        assertThat(firstPage.getRecords()).hasSize(100);
        assertThat(secondPage.getRecords()).hasSize(11);
        assertThat(secondPage.getRecords().get(0).getId()).isEqualTo(1009L);
        assertThat(secondPage.getRecords().get(10).getId()).isEqualTo(20L);
    }

    @Test
    void completedTimeSortsNullLastAndEscapedFileNameIsLiteral() {
        jdbcTemplate.update("DELETE FROM farm_print_job WHERE id IN (20, 21)");
        jdbcTemplate.update("INSERT INTO farm_print_file (id, original_name) VALUES (?, ?)",
                101L, "model%_part.gcode");
        insertJob(50L, 101L, null, 10L, null, 1, "COMPLETED", "2026-09-11 08:00:00",
                "2026-09-12 12:00:00");
        insertJob(51L, null, null, 10L, null, 1, "PRINTING", "2026-09-11 09:00:00", null);
        insertJob(52L, null, null, 10L, null, 1, "FAILED", "2026-09-11 10:00:00",
                "2026-09-12 11:00:00");

        Page<PrintJobSummaryVO> completedPage = printJobMapper.selectPageSummaries(
                new Page<>(1, 10), List.of("COMPLETED", "FAILED", "PRINTING"), null, null,
                10L, false, null, PrintJobTimeField.COMPLETED_AT, null, null);
        Page<PrintJobSummaryVO> escapedFilePage = printJobMapper.selectPageSummaries(
                new Page<>(1, 10), List.of("COMPLETED"), null, null,
                10L, false, "\\%\\_", PrintJobTimeField.COMPLETED_AT, null, null);

        assertThat(completedPage.getRecords()).extracting(PrintJobSummaryVO::getId)
                .containsExactly(50L, 52L, 51L);
        assertThat(escapedFilePage.getTotal()).isEqualTo(1);
        assertThat(escapedFilePage.getRecords().get(0).getId()).isEqualTo(50L);
    }

    @Test
    void detailUsesJoinAndOwnershipFilterAndReturnsDetailType() {
        PrintJobDetailVO adminDetail = printJobMapper.selectAccessibleDetail(20L, 99L, true);
        PrintJobDetailVO ownDetail = printJobMapper.selectAccessibleDetail(20L, 10L, false);
        PrintJobDetailVO otherDetail = printJobMapper.selectAccessibleDetail(20L, 11L, false);
        PrintJobDetailVO missingDetail = printJobMapper.selectAccessibleDetail(999L, 10L, false);

        assertThat(adminDetail).isNotNull().isInstanceOf(PrintJobDetailVO.class);
        assertThat(ownDetail).isNotNull();
        assertThat(otherDetail).isNull();
        assertThat(missingDetail).isNull();
    }

    @Test
    void summaryQueriesHaveOneFixedJoinShapeInsteadOfPerRowLookups() throws Exception {
        String mapperXml;
        try (var inputStream = new ClassPathResource("mapper/PrintJobMapper.xml").getInputStream()) {
            mapperXml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }

        assertThat(mapperXml).contains("<sql id=\"jobSummaryJoins\">\n"
                + "        FROM farm_print_job j\n"
                + "        LEFT JOIN farm_print_file f ON f.id = j.file_id\n"
                + "        LEFT JOIN farm_printer p ON p.id = j.printer_id\n"
                + "        LEFT JOIN farm_user creator_user ON creator_user.id = j.user_id\n"
                + "        LEFT JOIN farm_user operator_user ON operator_user.id = j.operator_id");
        assertThat(mapperXml).contains("<select id=\"selectQueuedSummaries\"");
        assertThat(mapperXml).contains("<select id=\"selectPageSummaries\"");
        assertThat(mapperXml).contains("<select id=\"selectAccessibleDetail\"");
    }

    private void insertJob(Long id, Long fileId, Long printerId, Long userId, Long operatorId,
                           int priority, String status, String createdAt) {
        insertJob(id, fileId, printerId, userId, operatorId, priority, status, createdAt, null);
    }

    private void insertJob(Long id, Long fileId, Long printerId, Long userId, Long operatorId,
                           int priority, String status, String createdAt, String completedAt) {
        jdbcTemplate.update("""
                INSERT INTO farm_print_job
                    (id, file_id, printer_id, user_id, operator_id, priority, status,
                     progress, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, fileId, printerId, userId, operatorId, priority, status,
                BigDecimal.ZERO, completedAt, createdAt, createdAt);
    }

    private void dropTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS farm_print_job");
        jdbcTemplate.execute("DROP TABLE IF EXISTS farm_printer");
        jdbcTemplate.execute("DROP TABLE IF EXISTS farm_print_file");
        jdbcTemplate.execute("DROP TABLE IF EXISTS farm_user");
    }

    @TestConfiguration
    static class SqlCountConfiguration {

        @Bean
        SqlStatementCountInterceptor sqlStatementCountInterceptor() {
            return new SqlStatementCountInterceptor();
        }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class SqlStatementCountInterceptor implements Interceptor {

        private final AtomicInteger statements = new AtomicInteger();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            statements.incrementAndGet();
            return invocation.proceed();
        }

        void reset() {
            statements.set(0);
        }

        int count() {
            return statements.get();
        }
    }
}
