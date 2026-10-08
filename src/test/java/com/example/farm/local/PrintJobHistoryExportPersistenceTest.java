package com.example.farm.local;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.export.ExportDocument;
import com.example.farm.common.export.ExportFormat;
import com.example.farm.common.export.ExportReport;
import com.example.farm.common.export.ReportExportService;
import com.example.farm.config.ExportProperties;
import com.example.farm.config.MybatisPlusConfig;
import com.example.farm.entity.dto.request.PrintJobHistoryExportRequest;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.service.PrintJobHistoryExportService;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real SQLite SQL, isolated from live databases, printer adapters and network storage. */
class PrintJobHistoryExportPersistenceTest {
    private Path fixture;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private ExportProperties properties;
    private ReportExportService renderer;
    private PrintJobHistoryExportService service;
    private PrintJobMapper mapper;
    private ExportReport rendered;
    private StatementRecorder statements;

    @BeforeEach
    void setup() throws Exception {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        fixture = Files.createTempDirectory(target, "history-export-");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + fixture.resolve("history.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(1);
        jdbc = new JdbcTemplate(dataSource);
        createTables();
        statements = new StatementRecorder();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(statements);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setDatabaseIdProvider(new MybatisPlusConfig().databaseIdProvider());
        factory.setMapperLocations(new ClassPathResource("mapper/PrintJobMapper.xml"));
        MapperFactoryBean<PrintJobMapper> mapperFactory = new MapperFactoryBean<>(PrintJobMapper.class);
        mapperFactory.setSqlSessionFactory(factory.getObject());
        mapperFactory.afterPropertiesSet();
        properties = new ExportProperties();
        renderer = mock(ReportExportService.class);
        when(renderer.render(any(), any(), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            rendered = invocation.getArgument(0);
            return new ExportDocument("fixture.pdf", "application/pdf", new byte[] {37, 80, 68, 70});
        });
        mapper = mapperFactory.getObject();
        service = new PrintJobHistoryExportService(mapper, renderer, properties,
                new DataSourceTransactionManager(dataSource));
        login(10L, "OPERATOR");
        jdbc.update("INSERT INTO farm_print_file VALUES (100, 'new-file.gcode', 'PLA', 0.4)");
        jdbc.update("INSERT INTO farm_printer VALUES (200, 'New printer', 'NEW-01', NULL, 0)");
        jdbc.update("INSERT INTO farm_user VALUES (10, 'new-creator'), (11, 'other'), (12, 'new-operator')");
        insertJob(9007199254740993L, 10L, "COMPLETED", "2026-10-08 10:00:00", "2026-10-08 11:30:30", 2);
        insertJob(2L, 10L, "FAILED", "2026-10-08 12:00:00", "2026-10-08 12:15:00", 1);
        insertJob(3L, 11L, "CANCELLED", "2026-10-08 13:00:00", "2026-10-08 13:10:00", 1);
        insertJob(4L, 10L, "PRINTING", "2026-10-08 14:00:00", null, 1);
        insertJob(5L, 10L, "EXTERNAL", "2026-10-08 15:00:00", "2026-10-08 15:10:00", 1);
        statements.sql.clear();
    }

    @AfterEach
    void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        if (dataSource != null) dataSource.close();
        if (fixture != null) {
            for (String filename : List.of("history.db", "history.db-journal", "history.db-wal", "history.db-shm")) {
                Files.deleteIfExists(fixture.resolve(filename));
            }
            Files.deleteIfExists(fixture);
        }
    }

    @Test
    void operatorGetsOnlyOwnTerminalHistoryAndSingleBoundedQuery() {
        PrintJobHistoryExportRequest request = request();
        request.setUserId(11L);
        service.export(request);
        assertThat(ids()).containsExactly("2", "9007199254740993");
        assertThat(statements.sql).hasSize(1);
        assertThat(statements.sql.getFirst()).contains("LIMIT ?", "j.user_id = ?").doesNotContain("COUNT(");
        assertThat(summary("历史记录条数")).isEqualTo(2);
        assertThat(summary("已完成")).isEqualTo(1);
        assertThat(summary("失败")).isEqualTo(1);
        assertThat(summary("已取消")).isEqualTo(0);
        assertThat(summary("筛选条件").toString()).contains("创建人ID=10").doesNotContain("创建人ID=11");
        assertThat(summary("统计口径").toString()).contains("不代表打印件数", "不含设备外部打印");
    }

    @Test
    void administratorCanExportAllOwnersOrFilterCreator() {
        login(10L, "ADMIN");
        service.export(request());
        assertThat(ids()).containsExactly("3", "2", "9007199254740993");
        PrintJobHistoryExportRequest filtered = request();
        filtered.setUserId(11L);
        service.export(filtered);
        assertThat(ids()).containsExactly("3");
    }

    @Test
    void completionRangeIsInclusiveAndIgnoresCreatedTime() {
        PrintJobHistoryExportRequest request = request();
        request.setStartTime(LocalDateTime.parse("2026-10-08T11:30:30"));
        request.setEndTime(LocalDateTime.parse("2026-10-08T11:30:30"));
        service.export(request);
        assertThat(ids()).containsExactly("9007199254740993");
        assertThat(summary("时区")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void mixedSqliteTimestampFormatsPreserveExactBoundariesAndChronologicalOrder() {
        insertJob(7L, 10L, "COMPLETED", "2026-10-08T10:00", "2026-10-08T11:30:30", 1);
        service.export(request());
        assertThat(ids()).containsExactly("2", "9007199254740993", "7");
        PrintJobHistoryExportRequest request = request();
        request.setStartTime(LocalDateTime.parse("2026-10-08T12:15:00"));
        request.setEndTime(LocalDateTime.parse("2026-10-08T12:15:00"));
        service.export(request);
        assertThat(ids()).containsExactly("2");
        jdbc.update("UPDATE farm_print_job SET completed_at = '2026-10-08T12:15' WHERE id = 2");
        service.export(request);
        assertThat(ids()).containsExactly("2");
        jdbc.update("UPDATE farm_print_job SET completed_at = '2026-10-08T12:15:00.000000001' WHERE id = 2");
        service.export(request);
        assertThat(ids()).isEmpty();
        request.setEndTime(LocalDateTime.parse("2026-10-08T12:15:00.000000001"));
        service.export(request);
        assertThat(ids()).containsExactly("2");
    }

    @ParameterizedTest
    @CsvSource({"PDF,ADMIN,3", "PDF,OPERATOR,2", "XLSX,ADMIN,3", "XLSX,OPERATOR,2"})
    void actualBusinessQueryAndRendererProduceReadableScopedDocuments(String format, String role, int expectedRows) throws Exception {
        login(10L, role);
        jdbc.update("UPDATE farm_print_job SET file_name_snapshot = '齿轮座.gcode', printer_name_snapshot = '一号打印机',"
                + "creator_username_snapshot = '张工', operator_username_snapshot = '李操作员' WHERE user_id = 10");
        PrintJobHistoryExportService real = new PrintJobHistoryExportService(mapper, new ReportExportService(),
                properties, new DataSourceTransactionManager(dataSource));
        PrintJobHistoryExportRequest request = request();
        request.setFormat(ExportFormat.valueOf(format));
        ExportDocument document = real.export(request);
        if ("PDF".equals(format)) {
            try (var pdf = Loader.loadPDF(document.content())) {
                String text = new PDFTextStripper().getText(pdf);
                String compactText = text.replaceAll("\\s+", "");
                assertThat(compactText).contains("打印历史", "齿轮座.gcode", "一号打印机", "9007199254740993", "李操作员",
                        "经过时长", "包含暂停", "不代表打印件数", "历史明细·" + expectedRows + "条记录");
                // Resource scope depends on exported data, independent of card/table presentation.
                if ("OPERATOR".equals(role)) assertThat(compactText).doesNotContain("old-file.gcode", "old-creator");
                else assertThat(compactText).contains("old-file.gcode", "old-creator");
            }
        } else {
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(document.content()))) {
                var details = workbook.getSheet("明细");
                // Opening the official workbook must immediately show the printable record table.
                assertThat(workbook.getSheetName(0)).isEqualTo("明细");
                assertThat(workbook.getActiveSheetIndex()).isZero();
                assertThat(workbook.getFirstVisibleTab()).isZero();
                assertThat(workbook.isSheetHidden(0)).isFalse();
                assertThat(details.isSelected()).isTrue();
                assertThat(details.getTables()).hasSize(1);
                var recordTable = details.getTables().getFirst();
                assertThat(recordTable.getName()).isEqualTo("ExportDetails");
                assertThat(recordTable.getStartRowIndex()).isZero();
                assertThat(recordTable.getEndRowIndex()).isEqualTo(expectedRows);
                assertThat(recordTable.getColumnCount()).isEqualTo(13);
                assertThat(details.getRow(0).getLastCellNum()).isEqualTo((short) 13);
                assertThat(details.getLastRowNum()).isEqualTo(expectedRows);
                assertThat(details.getRow(expectedRows).getCell(0).getCellType()).isEqualTo(CellType.STRING);
                assertThat(details.getRow(expectedRows).getCell(0).getStringCellValue()).isEqualTo("9007199254740993");
                assertThat(details.getRow(expectedRows).getCell(1).getStringCellValue()).isEqualTo("齿轮座.gcode");
                assertThat(details.getRow(expectedRows).getCell(10).getNumericCellValue()).isEqualTo(90.5);
                assertThat(details.getRow(expectedRows).getCell(11).getNumericCellValue()).isEqualTo(2);
                var summary = workbook.getSheet("汇总");
                var count = java.util.stream.StreamSupport.stream(summary.spliterator(), false)
                        .filter(row -> row.getCell(0) != null && "历史记录条数".equals(row.getCell(0).toString()))
                        .findFirst().orElseThrow();
                assertThat(count.getCell(1).getNumericCellValue()).isEqualTo(expectedRows);
            }
        }
        if ("ADMIN".equals(role)) {
            Path preview = Path.of("target", "history-export-preview");
            Files.createDirectories(preview);
            Files.write(preview.resolve("business-sample." + format.toLowerCase(java.util.Locale.ROOT)), document.content());
        }
    }

    @Test
    void namesComeFromSnapshotsAfterLinkedResourcesAreRenamedOrDeleted() {
        service.export(request());
        List<Object> row = rendered.rows().get(1);
        assertThat(row.subList(1, 6)).containsExactly("old-file.gcode", "Old printer", "OLD-01", "old-creator", "old-operator");
        jdbc.update("DELETE FROM farm_print_file");
        jdbc.update("DELETE FROM farm_printer");
        jdbc.update("DELETE FROM farm_user");
        service.export(request());
        assertThat(rendered.rows().get(1).subList(1, 6)).containsExactly("old-file.gcode", "Old printer", "OLD-01", "old-creator", "old-operator");
    }

    @Test
    void legacyCancelledRowsAreNormalizedAndMissingDatesRemainBlank() {
        insertJob(6L, 10L, "CANCELED", null, null, null);
        PrintJobHistoryExportRequest request = request();
        request.setStatuses(List.of(" canceled "));
        service.export(request);
        assertThat(ids()).containsExactly("6");
        assertThat(rendered.rows().getFirst().get(6)).isEqualTo("已取消");
        assertThat(rendered.rows().getFirst().subList(8, 12)).containsExactly(null, null, null, null);
        assertThat(summary("已取消")).isEqualTo(1);
    }

    @Test
    void durationIncludesElapsedSecondsAndInvalidChronologyRemainsBlank() {
        service.export(request());
        assertThat(rendered.rows().get(1).get(10)).isEqualTo(new BigDecimal("90.50"));
        assertThat(rendered.rows().get(1).get(11)).isEqualTo(2);
        assertThat(summary("经过时长合计（分钟）")).isEqualTo(new BigDecimal("105.50"));
        jdbc.update("UPDATE farm_print_job SET started_at = '2026-10-09 10:00:00' WHERE id = 2");
        service.export(request());
        assertThat(rendered.rows().getFirst().get(10)).isNull();
        assertThat(summary("经过时长合计（分钟）")).isEqualTo(new BigDecimal("90.50"));
    }

    @Test
    void fileNameUsesCaseInsensitiveLiteralMatchingAndTrim() {
        jdbc.update("UPDATE farm_print_file SET original_name = ? WHERE id = 100", "Part_50%\\test.gcode");
        PrintJobHistoryExportRequest request = request();
        request.setFileName("  _50%\\TEST  ");
        service.export(request);
        assertThat(ids()).containsExactly("2", "9007199254740993");
        request.setFileName("_50X");
        service.export(request);
        assertThat(rendered.rows()).isEmpty();
    }

    @Test
    void printerAndSelectedStatusFiltersAreCombinedWithoutPagination() {
        PrintJobHistoryExportRequest request = request();
        request.setPrinterId(200L);
        request.setStatuses(List.of("failed"));
        service.export(request);
        assertThat(ids()).containsExactly("2");
        request.setPrinterId(201L);
        service.export(request);
        assertThat(rendered.rows()).isEmpty();
    }

    @Test
    void emptySelectionStillBuildsReportWithZeroMetrics() {
        jdbc.update("DELETE FROM farm_print_job");
        service.export(request());
        assertThat(rendered.rows()).isEmpty();
        assertThat(rendered.columns()).hasSize(13);
        assertThat(summary("历史记录条数")).isEqualTo(0);
        assertThat(summary("经过时长合计（分钟）")).isEqualTo(new BigDecimal("0.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PDF", "XLSX"})
    void formatSpecificLimitIsCheckedWithoutTruncation(String format) {
        properties.setPdfMaxRows(1);
        properties.setXlsxMaxRows(1);
        PrintJobHistoryExportRequest request = request();
        request.setFormat(ExportFormat.valueOf(format));
        assertBusiness(422, () -> service.export(request));
        assertThat(statements.sql).hasSize(1);
        verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PDF", "XLSX"})
    void exactConfiguredLimitSucceeds(String format) {
        properties.setPdfMaxRows(2);
        properties.setXlsxMaxRows(2);
        PrintJobHistoryExportRequest request = request();
        request.setFormat(ExportFormat.valueOf(format));
        service.export(request);
        assertThat(rendered.rows()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PRINTING", "UNKNOWN", "", "null"})
    void activeAndInvalidStatusesFailBeforeQuery(String status) {
        PrintJobHistoryExportRequest request = request();
        request.setStatuses(Arrays.asList("null".equals(status) ? null : status));
        assertBusiness(400, () -> service.export(request));
        assertThat(statements.sql).isEmpty();
        verifyNoInteractions(renderer);
    }

    @Test
    void invalidRangeAndTooLongFilenameAreRejectedBeforeQuery() {
        PrintJobHistoryExportRequest request = request();
        request.setStartTime(LocalDateTime.parse("2026-10-09T00:00:00"));
        request.setEndTime(LocalDateTime.parse("2026-10-08T00:00:00"));
        assertBusiness(400, () -> service.export(request));
        request.setStartTime(null);
        request.setFileName("x".repeat(256));
        assertBusiness(400, () -> service.export(request));
        assertThat(statements.sql).isEmpty();
    }

    @Test
    void missingRequestFormatAndNonPositiveFilterIdsFailBeforeQuery() {
        assertBusiness(400, () -> service.export(null));
        PrintJobHistoryExportRequest request = request();
        request.setFormat(null);
        assertBusiness(400, () -> service.export(request));
        request.setFormat(ExportFormat.PDF);
        request.setPrinterId(0L);
        assertBusiness(400, () -> service.export(request));
        request.setPrinterId(null);
        request.setUserId(-1L);
        assertBusiness(400, () -> service.export(request));
        assertThat(statements.sql).isEmpty();
    }

    @Test
    void directServiceAccessCannotBypassAuthenticationOrRoleBoundary() {
        SecurityContextHolder.clearContext();
        assertBusiness(403, () -> service.export(request()));
        login(10L, "VIEWER");
        assertBusiness(403, () -> service.export(request()));
        login(0L, "ADMIN");
        assertBusiness(403, () -> service.export(request()));
        assertThat(statements.sql).isEmpty();
        verifyNoInteractions(renderer);
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE farm_user (id INTEGER PRIMARY KEY, username TEXT)");
        jdbc.execute("CREATE TABLE farm_print_file (id INTEGER PRIMARY KEY, original_name TEXT, material_type TEXT, nozzle_size NUMERIC)");
        jdbc.execute("CREATE TABLE farm_printer (id INTEGER PRIMARY KEY, name TEXT, machine_number TEXT, current_job_id INTEGER, is_safe_to_print INTEGER)");
        jdbc.execute("""
                CREATE TABLE farm_print_job (
                    id INTEGER PRIMARY KEY, file_id INTEGER, printer_id INTEGER, user_id INTEGER,
                    operator_id INTEGER, file_name_snapshot TEXT, printer_name_snapshot TEXT,
                    machine_number_snapshot TEXT, creator_username_snapshot TEXT, operator_username_snapshot TEXT,
                    retry_of_job_id INTEGER, root_job_id INTEGER, attempt_number INTEGER, priority INTEGER,
                    status TEXT, progress NUMERIC, started_at TEXT, completed_at TEXT, error_reason TEXT,
                    created_at TEXT, updated_at TEXT)
                """);
    }

    private void insertJob(long id, long owner, String status, String started, String completed, Integer attempt) {
        jdbc.update("""
                INSERT INTO farm_print_job (id, file_id, printer_id, user_id, operator_id,
                    file_name_snapshot, printer_name_snapshot, machine_number_snapshot,
                    creator_username_snapshot, operator_username_snapshot, status,
                    started_at, completed_at, error_reason, attempt_number, created_at, updated_at)
                VALUES (?, 100, 200, ?, 12, 'old-file.gcode', 'Old printer', 'OLD-01',
                        'old-creator', 'old-operator', ?, ?, ?, 'fixture reason', ?,
                        '2026-09-01 01:02:03', '2026-10-08 01:02:03')
                """, id, owner, status, started, completed, attempt);
    }

    private static void login(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId,
                "fixture-user", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static PrintJobHistoryExportRequest request() {
        PrintJobHistoryExportRequest request = new PrintJobHistoryExportRequest();
        request.setFormat(ExportFormat.PDF);
        return request;
    }

    private List<String> ids() {
        return rendered.rows().stream().map(row -> (String) row.getFirst()).toList();
    }

    private Object summary(String label) {
        return rendered.summary().stream().filter(item -> item.label().equals(label)).findFirst().orElseThrow().value();
    }

    private static void assertBusiness(long code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static class StatementRecorder implements Interceptor {
        private final List<String> sql = new ArrayList<>();
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            sql.add(((StatementHandler) invocation.getTarget()).getBoundSql().getSql());
            return invocation.proceed();
        }
    }
}
