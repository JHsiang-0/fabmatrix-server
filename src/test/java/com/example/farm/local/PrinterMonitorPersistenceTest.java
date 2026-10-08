package com.example.farm.local;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterProtocolDetector;
import com.example.farm.protocol.RrfApiClient;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.impl.PrinterServiceImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real SQLite/MyBatis transactions; only isolated target fixtures are modified. */
@SpringJUnitConfig(PrinterMonitorPersistenceTest.Fixture.class)
@TestPropertySource(properties = {
        "farm.monitor.enabled=false",
        "farm.scheduler.enabled=false",
        "farm.websocket.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrinterMonitorPersistenceTest {

    private static final Path DATA_DIR = createDataDirectory();

    @Autowired
    private PrinterService printerService;
    @Autowired
    private PrinterCacheService printerCacheService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private HikariDataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MacAddressUtil macAddressUtil;
    @Autowired
    private PrinterProtocolDetector protocolDetector;
    @Autowired
    private RrfApiClient rrfApiClient;

    @BeforeEach
    void resetFixture() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS simulate_runtime_update");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS block_second_monitor_write");
        jdbcTemplate.update("DELETE FROM farm_printer");
        reset(printerCacheService, macAddressUtil, protocolDetector, rrfApiClient);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, "fixture-admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearAuthenticationAndCheckNoDeviceCalls() {
        SecurityContextHolder.clearContext();
        verifyNoInteractions(macAddressUtil, protocolDetector, rrfApiClient);
    }

    @AfterAll
    void removeFixture() throws IOException {
        dataSource.close();
        for (String name : List.of("farm.db", "farm.db-journal", "farm.db-wal", "farm.db-shm")) {
            Files.deleteIfExists(DATA_DIR.resolve(name));
        }
        Files.deleteIfExists(DATA_DIR);
    }

    @Test
    void singleMonitorWritePreservesRuntimeChangesMadeAfterTheInitialRead() {
        insertPrinter(1L, "192.168.10.1");
        simulateRuntimeUpdateBeforeMonitorWrite();

        printerService.updateMonitorEnabled(1L, true);

        assertMonitorEnabled(1L, true);
        assertRuntimeChangesPreserved(1L);
        verify(printerCacheService).refreshPrinterCache();
    }

    @Test
    void batchMonitorWriteDeduplicatesIdsAndPreservesRuntimeChanges() {
        insertPrinter(1L, "192.168.10.1");
        insertPrinter(2L, "192.168.10.2");
        simulateRuntimeUpdateBeforeMonitorWrite();

        int updated = printerService.batchUpdateMonitorEnabled(List.of(1L, 2L, 1L), true);

        assertThat(updated).isEqualTo(2);
        assertMonitorEnabled(1L, true);
        assertMonitorEnabled(2L, true);
        assertRuntimeChangesPreserved(1L);
        assertRuntimeChangesPreserved(2L);
        verify(printerCacheService).refreshPrinterCache();
    }

    @Test
    void repeatedSameValueWritesRemainSuccessfulAndPreserveRuntimeColumns() {
        insertPrinter(1L, "192.168.10.1");
        insertPrinter(2L, "192.168.10.2");
        Map<String, Object> first = nonMonitorColumns(1L);
        Map<String, Object> second = nonMonitorColumns(2L);

        printerService.updateMonitorEnabled(1L, true);
        printerService.updateMonitorEnabled(1L, true);
        assertThat(printerService.batchUpdateMonitorEnabled(List.of(1L, 2L, 1L), true)).isEqualTo(2);
        assertThat(printerService.batchUpdateMonitorEnabled(List.of(1L, 2L), true)).isEqualTo(2);

        assertThat(nonMonitorColumns(1L)).isEqualTo(first);
        assertThat(nonMonitorColumns(2L)).isEqualTo(second);
        verify(printerCacheService, times(4)).refreshPrinterCache();
    }

    @Test
    void unknownBatchIdIsRejectedBeforeAnyPrinterIsChanged() {
        insertPrinter(1L, "192.168.10.1");
        Map<String, Object> before = allColumns(1L);

        assertThatThrownBy(() -> printerService.batchUpdateMonitorEnabled(List.of(1L, 999L), true))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(404));

        assertThat(allColumns(1L)).isEqualTo(before);
        verifyNoInteractions(printerCacheService);
    }

    @Test
    void zeroChangedRowsUseTheStoredSwitchForIdempotentSuccess() {
        insertPrinter(1L, "192.168.10.1");
        printerService.updateMonitorEnabled(1L, true);
        reset(printerCacheService);
        jdbcTemplate.execute("""
                CREATE TRIGGER block_second_monitor_write
                BEFORE UPDATE OF monitor_enabled ON farm_printer
                WHEN NEW.monitor_enabled = OLD.monitor_enabled
                BEGIN
                    SELECT RAISE(IGNORE);
                END
                """);

        printerService.updateMonitorEnabled(1L, true);

        assertMonitorEnabled(1L, true);
        verify(printerCacheService).refreshPrinterCache();
    }

    @Test
    void monitorReadChoosesLockingSqlOnlyForMySql() throws Exception {
        for (String dialect : new String[] {null, "sqlite", "mysql"}) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setDatabaseId(dialect);
            try (var stream = new ClassPathResource("mapper/PrinterMapper.xml").getInputStream()) {
                new XMLMapperBuilder(stream, configuration, "mapper/PrinterMapper.xml",
                        configuration.getSqlFragments()).parse();
            }
            String sql = configuration.getMappedStatement(
                    PrinterMapper.class.getName() + ".selectMonitorEnabledForUpdate")
                    .getBoundSql(Map.of("id", 1L)).getSql();
            if ("mysql".equals(dialect)) {
                assertThat(sql).contains("FOR UPDATE");
            } else {
                assertThat(sql).doesNotContain("FOR UPDATE");
            }
        }
    }

    @Test
    void zeroAffectedRowsOnSecondPrinterRollBackTheFirstWrite() {
        insertPrinter(1L, "192.168.10.1");
        insertPrinter(2L, "192.168.10.2");
        Map<String, Object> first = allColumns(1L);
        Map<String, Object> second = allColumns(2L);
        blockSecondWrite("IGNORE");

        assertThatThrownBy(() -> printerService.batchUpdateMonitorEnabled(List.of(1L, 2L), true))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));

        assertThat(allColumns(1L)).isEqualTo(first);
        assertThat(allColumns(2L)).isEqualTo(second);
        verifyNoInteractions(printerCacheService);
    }

    @Test
    void databaseFailureOnSecondPrinterRollsBackTheFirstWrite() {
        insertPrinter(1L, "192.168.10.1");
        insertPrinter(2L, "192.168.10.2");
        Map<String, Object> first = allColumns(1L);
        Map<String, Object> second = allColumns(2L);
        blockSecondWrite("ABORT, 'isolated monitor write failure'");

        assertThatThrownBy(() -> printerService.batchUpdateMonitorEnabled(List.of(1L, 2L), true))
                .isInstanceOf(DataAccessException.class);

        assertThat(allColumns(1L)).isEqualTo(first);
        assertThat(allColumns(2L)).isEqualTo(second);
        verifyNoInteractions(printerCacheService);
    }

    @Test
    void enablingPrintersWithoutAddressesDoesNotMakeThemPollingTargets() {
        insertPrinter(1L, null);
        insertPrinter(2L, "");
        insertPrinter(3L, " \t ");
        insertPrinter(4L, "192.168.10.4");
        insertPrinter(5L, "192.168.10.5");

        printerService.updateMonitorEnabled(1L, true);
        assertThat(printerService.batchUpdateMonitorEnabled(List.of(2L, 3L, 4L), true)).isEqualTo(3);

        assertMonitorEnabled(1L, true);
        assertMonitorEnabled(2L, true);
        assertMonitorEnabled(3L, true);
        assertThat(printerService.listMonitorPrinters()).extracting(Printer::getId).containsExactly(4L);
        assertMonitorEnabled(5L, false);
    }

    @Test
    void cacheRefreshWaitsUntilTheSurroundingTransactionCommits() {
        insertPrinter(1L, "192.168.10.1");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            printerService.updateMonitorEnabled(1L, true);
            assertMonitorEnabled(1L, true);
            verifyNoInteractions(printerCacheService);
        });

        assertMonitorEnabled(1L, true);
        verify(printerCacheService).refreshPrinterCache();
    }

    @Test
    void rolledBackSurroundingTransactionDoesNotPublishACacheRefresh() {
        insertPrinter(1L, "192.168.10.1");
        Map<String, Object> before = allColumns(1L);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            printerService.updateMonitorEnabled(1L, true);
            assertMonitorEnabled(1L, true);
            verifyNoInteractions(printerCacheService);
            status.setRollbackOnly();
        });

        assertThat(allColumns(1L)).isEqualTo(before);
        verifyNoInteractions(printerCacheService);
    }

    private void insertPrinter(long id, String ipAddress) {
        jdbcTemplate.update("""
                INSERT INTO farm_printer
                    (id, name, ip_address, firmware_type, status, current_job_id, is_safe_to_print,
                     connection_status, last_seen_at, last_communication_error, consecutive_failure_count,
                     last_known_status, last_protocol_state, last_device_uptime)
                VALUES (?, ?, ?, 'RRF', 'PRINTING', ?, 1, 'ONLINE', '2026-01-02 03:04:05',
                        'prior communication detail', 2, 'PRINTING', 'processing', 1234)
                """, id, "Fixture printer " + id, ipAddress, 500L + id);
    }

    /** A BEFORE trigger simulates fresh device data arriving between select and update. */
    private void simulateRuntimeUpdateBeforeMonitorWrite() {
        jdbcTemplate.execute("""
                CREATE TRIGGER simulate_runtime_update
                BEFORE UPDATE OF monitor_enabled ON farm_printer
                WHEN NEW.monitor_enabled <> OLD.monitor_enabled
                BEGIN
                    UPDATE farm_printer SET status = 'PAUSED', current_job_id = 700 + OLD.id,
                        is_safe_to_print = 0, ip_address = '192.168.20.' || OLD.id,
                        connection_status = 'OFFLINE', last_seen_at = '2026-02-03 04:05:06',
                        last_communication_error = 'fresh runtime communication detail',
                        consecutive_failure_count = 4, last_known_status = 'PAUSED',
                        last_protocol_state = 'paused', last_device_uptime = 5678
                    WHERE id = OLD.id;
                END
                """);
    }

    private void assertRuntimeChangesPreserved(long id) {
        Map<String, Object> row = allColumns(id);
        assertThat(((Number) row.get("current_job_id")).longValue()).isEqualTo(700L + id);
        assertThat(((Number) row.get("is_safe_to_print")).intValue()).isZero();
        assertThat(((Number) row.get("consecutive_failure_count")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("last_device_uptime")).longValue()).isEqualTo(5678L);
        assertThat(row).containsEntry("status", "PAUSED")
                .containsEntry("ip_address", "192.168.20." + id)
                .containsEntry("connection_status", "OFFLINE")
                .containsEntry("last_seen_at", "2026-02-03 04:05:06")
                .containsEntry("last_communication_error", "fresh runtime communication detail")
                .containsEntry("last_known_status", "PAUSED")
                .containsEntry("last_protocol_state", "paused");
    }

    private void blockSecondWrite(String sqliteRaise) {
        jdbcTemplate.execute("""
                CREATE TRIGGER block_second_monitor_write
                BEFORE UPDATE OF monitor_enabled ON farm_printer
                WHEN OLD.id = 2
                BEGIN
                    SELECT RAISE(%s);
                END
                """.formatted(sqliteRaise));
    }

    private void assertMonitorEnabled(long id, boolean enabled) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT monitor_enabled FROM farm_printer WHERE id = ?", Integer.class, id))
                .isEqualTo(enabled ? 1 : 0);
    }

    private Map<String, Object> allColumns(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM farm_printer WHERE id = ?", id);
    }

    private Map<String, Object> nonMonitorColumns(long id) {
        Map<String, Object> row = new LinkedHashMap<>(allColumns(id));
        row.remove("monitor_enabled");
        row.remove("updated_at");
        return row;
    }

    private static Path createDataDirectory() {
        try {
            Path target = Path.of("target").toAbsolutePath().normalize();
            Files.createDirectories(target);
            return Files.createTempDirectory(target, "printer-monitor-persistence-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Minimal context: no application scanning, scheduling, sockets, or protocol clients. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Fixture {

        @Bean
        HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl("jdbc:sqlite:" + DATA_DIR.resolve("farm.db"));
            source.setDriverClassName("org.sqlite.JDBC");
            source.setMaximumPoolSize(1);
            source.setConnectionTimeout(1000);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/local-schema.sql"))
                    .execute(source);
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/PrinterMapper.xml"));
            return factory.getObject();
        }

        @Bean
        PrinterMapper printerMapper(SqlSessionFactory sqlSessionFactory) throws Exception {
            MapperFactoryBean<PrinterMapper> factory = new MapperFactoryBean<>(PrinterMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            factory.afterPropertiesSet();
            return factory.getObject();
        }

        @Bean
        PrinterCacheService printerCacheService() {
            return mock(PrinterCacheService.class);
        }

        @Bean
        MacAddressUtil macAddressUtil() {
            return mock(MacAddressUtil.class);
        }

        @Bean
        PrinterProtocolDetector protocolDetector() {
            return mock(PrinterProtocolDetector.class);
        }

        @Bean
        RrfApiClient rrfApiClient() {
            return mock(RrfApiClient.class);
        }

        @Bean
        PrinterService printerService(PrinterCacheService printerCacheService, MacAddressUtil macAddressUtil,
                                      PrinterProtocolDetector protocolDetector, RrfApiClient rrfApiClient) {
            return new PrinterServiceImpl(printerCacheService, macAddressUtil, protocolDetector, rrfApiClient);
        }
    }
}
