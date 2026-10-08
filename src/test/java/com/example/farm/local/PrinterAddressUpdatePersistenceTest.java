package com.example.farm.local;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.PrinterAddressUpdateService;
import com.example.farm.service.PrinterAddressUpdateService.Change;
import com.example.farm.service.PrinterCacheService;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exercises actual SQLite unique constraints and MyBatis transactions in isolated target fixtures. */
@SpringJUnitConfig(PrinterAddressUpdatePersistenceTest.Fixture.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrinterAddressUpdatePersistenceTest {

    private static final Path DATA_DIR = createDataDirectory();

    @Autowired
    private PrinterAddressUpdateService service;
    @Autowired
    private PrinterCacheService cache;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private HikariDataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PrinterMapper printerMapper;

    @BeforeEach
    void resetFixture() {
        for (String trigger : List.of("simulate_runtime_update", "block_address_write", "change_identity")) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
        jdbc.update("DELETE FROM farm_printer");
        reset(cache);
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
    void updatesOnlyAddressHealthAndTimestampOnSinglePrinter() {
        insertPrinter(1, "192.168.10.1");
        Map<String, Object> before = preservedColumns(1);

        assertThat(service.apply(List.of(change(1, "192.168.10.1", "192.168.10.21"))))
                .containsExactly(1L);

        assertThat(row(1)).containsEntry("ip_address", "192.168.10.21")
                .containsEntry("connection_status", "UNKNOWN");
        assertThat(preservedColumns(1)).isEqualTo(before);
        verify(cache).refreshPrinterCache();
        verify(cache).clearStatusCache(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AABBCCDDEE01", "aa-bb-cc-dd-ee-01", "AA:BB:CC:DD:EE:01"})
    void restoresAnAddressWithEquivalentHistoricalMacFormats(String historicalMac) {
        insertPrinter(1, "192.168.10.1");
        jdbc.update("UPDATE farm_printer SET mac_address = ? WHERE id = 1", historicalMac);

        assertThat(service.apply(List.of(new Change(1L, "aa:bb:cc:dd:ee:01", "RRF",
                "192.168.10.1", "192.168.10.21")))).containsExactly(1L);
        assertThat(ip(1)).isEqualTo("192.168.10.21");
        assertThat(row(1)).containsEntry("mac_address", historicalMac);
    }

    @Test
    void updatesTwoAddressesAtomicallyAndReturnsIdsInLockOrder() {
        insertPrinter(1, "192.168.10.1");
        insertPrinter(2, "192.168.10.2");

        assertThat(service.apply(List.of(change(2, "192.168.10.2", "192.168.10.22"),
                change(1, "192.168.10.1", "192.168.10.21")))).containsExactly(1L, 2L);

        assertThat(ip(1)).isEqualTo("192.168.10.21");
        assertThat(ip(2)).isEqualTo("192.168.10.22");
        verify(cache).refreshPrinterCache();
        verify(cache).clearStatusCache(1L);
        verify(cache).clearStatusCache(2L);
    }

    @Test
    void exchangesAddressesWithoutViolatingTheUniqueIndex() {
        insertPrinter(1, "192.168.10.1");
        insertPrinter(2, "192.168.10.2");
        Map<String, Object> first = preservedColumns(1);
        Map<String, Object> second = preservedColumns(2);

        service.apply(List.of(change(1, "192.168.10.1", "192.168.10.2"),
                change(2, "192.168.10.2", "192.168.10.1")));

        assertThat(ip(1)).isEqualTo("192.168.10.2");
        assertThat(ip(2)).isEqualTo("192.168.10.1");
        assertThat(preservedColumns(1)).isEqualTo(first);
        assertThat(preservedColumns(2)).isEqualTo(second);
    }

    @Test
    void recoversARegisteredPrinterWhoseOldAddressIsNull() {
        insertPrinter(1, null);

        assertThat(service.apply(List.of(change(1, null, "192.168.10.21")))).containsExactly(1L);

        assertThat(ip(1)).isEqualTo("192.168.10.21");
    }

    @Test
    void rejectsAnAddressOccupiedByAnotherRegisteredPrinterWithoutReleasingIt() {
        insertPrinter(1, "192.168.10.1");
        insertPrinter(2, "192.168.10.2");
        Map<String, Object> first = row(1);
        Map<String, Object> second = row(2);

        assertConflict(List.of(change(1, "192.168.10.1", "192.168.10.2")));

        assertThat(row(1)).isEqualTo(first);
        assertThat(row(2)).isEqualTo(second);
        verifyNoInteractions(cache);
    }

    @Test
    void rejectsRepeatedNewAddressesBeforeChangingEitherPrinter() {
        insertPrinter(1, "192.168.10.1");
        insertPrinter(2, "192.168.10.2");

        assertConflict(List.of(change(1, "192.168.10.1", "192.168.10.21"),
                change(2, "192.168.10.2", "192.168.10.21")));

        assertThat(ip(1)).isEqualTo("192.168.10.1");
        assertThat(ip(2)).isEqualTo("192.168.10.2");
        verifyNoInteractions(cache);
    }

    @Test
    void rejectsDuplicateDeviceIds() {
        insertPrinter(1, "192.168.10.1");

        assertConflict(List.of(change(1, "192.168.10.1", "192.168.10.21"),
                change(1, "192.168.10.1", "192.168.10.22")));

        assertThat(ip(1)).isEqualTo("192.168.10.1");
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00-11-22-33-44-0a", "00112233440A", " 00:11:22:33:44:0a "})
    void rechecksEquivalentMacInsertedWhileNetworkDiscoveryWasRunning(String duplicateMac) {
        insertPrinter(10, "192.168.10.10");
        // 此结果代表扫描开始时唯一的资产；扫描期间管理员录入了不同字符串格式的同一身份。
        List<Change> discoveredChanges = List.of(change(10, "192.168.10.10", "192.168.10.21"));
        insertPrinter(11, "192.168.10.11");
        jdbc.update("UPDATE farm_printer SET mac_address = ?, monitor_enabled = 0 WHERE id = 11", duplicateMac);
        Map<String, Object> original = row(10);
        Map<String, Object> duplicate = row(11);

        assertThatThrownBy(() -> service.apply(discoveredChanges))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409))
                .hasMessageContaining("MAC");

        assertThat(row(10)).isEqualTo(original);
        assertThat(row(11)).isEqualTo(duplicate);
        verifyNoInteractions(cache);
    }

    @Test
    void rejectsRepeatedCanonicalMacWithinTheSameAddressUpdateBatch() {
        insertPrinter(10, "192.168.10.10");
        insertPrinter(11, "192.168.10.11");
        jdbc.update("UPDATE farm_printer SET mac_address = '00112233440a' WHERE id = 11");

        assertConflict(List.of(change(10, "192.168.10.10", "192.168.10.21"),
                new Change(11L, "00-11-22-33-44-0A", "RRF", "192.168.10.11", "192.168.10.22")));

        assertThat(ip(10)).isEqualTo("192.168.10.10");
        assertThat(ip(11)).isEqualTo("192.168.10.11");
        verifyNoInteractions(cache);
    }

    @Test
    void emptyAddressSyncSelectionDoesNotGenerateEmptyInClauses() {
        insertPrinter(1, "192.168.10.1");
        assertThat(printerMapper.selectAddressSyncPrintersForUpdate(List.of(), List.of(), List.of())).isEmpty();
    }

    @Test
    void rejectsAnUnknownDeviceAndLeavesOtherTargetsUntouched() {
        insertPrinter(1, "192.168.10.1");
        Map<String, Object> before = row(1);

        assertConflict(List.of(change(1, "192.168.10.1", "192.168.10.21"),
                change(99, "192.168.10.99", "192.168.10.23")));

        assertThat(row(1)).isEqualTo(before);
        verifyNoInteractions(cache);
    }

    @Test
    void rejectsAChangedMac() {
        insertPrinter(1, "192.168.10.1");
        jdbc.update("UPDATE farm_printer SET mac_address = '00:11:22:33:44:99' WHERE id = 1");

        assertConflict(List.of(change(1, "192.168.10.1", "192.168.10.21")));

        assertThat(ip(1)).isEqualTo("192.168.10.1");
        verifyNoInteractions(cache);
    }

    @Test
    void rejectsAChangedProtocol() {
        insertPrinter(1, "192.168.10.1");
        jdbc.update("UPDATE farm_printer SET firmware_type = 'KLIPPER' WHERE id = 1");

        assertConflict(List.of(change(1, "192.168.10.1", "192.168.10.21")));

        assertThat(ip(1)).isEqualTo("192.168.10.1");
        verifyNoInteractions(cache);
    }

    @Test
    void rejectsAChangedOldAddress() {
        insertPrinter(1, "192.168.10.1");

        assertConflict(List.of(change(1, "192.168.10.99", "192.168.10.21")));

        assertThat(ip(1)).isEqualTo("192.168.10.1");
        verifyNoInteractions(cache);
    }

    @Test
    void rejectsADeviceWhoseMonitoringWasDisabledDuringDiscovery() {
        insertPrinter(1, "192.168.10.1");
        jdbc.update("UPDATE farm_printer SET monitor_enabled = 0 WHERE id = 1");

        assertConflict(List.of(change(1, "192.168.10.1", "192.168.10.21")));

        assertThat(ip(1)).isEqualTo("192.168.10.1");
        verifyNoInteractions(cache);
    }

    @Test
    void acceptsEquivalentHistoricalMacAndProtocolSpellings() {
        insertPrinter(1, "192.168.10.1");
        jdbc.update("UPDATE farm_printer SET mac_address = 'aa-bb-cc-dd-ee-01', firmware_type = 'rrf' WHERE id = 1");

        service.apply(List.of(new Change(1L, "AA:BB:CC:DD:EE:01", "RRF",
                "192.168.10.1", "192.168.10.21")));

        assertThat(ip(1)).isEqualTo("192.168.10.21");
        assertThat(row(1)).containsEntry("mac_address", "aa-bb-cc-dd-ee-01")
                .containsEntry("firmware_type", "rrf");
    }

    @Test
    void rejectsInvalidIdentityEvenWhenTheDatabaseContainsTheSameBadValue() {
        insertPrinter(1, "192.168.10.1");
        for (String invalidMac : List.of("unavailable", "00:00:00:00:00:00",
                "FF:FF:FF:FF:FF:FF", "01:11:22:33:44:55")) {
            jdbc.update("UPDATE farm_printer SET mac_address = ? WHERE id = 1", invalidMac);
            assertConflict(List.of(new Change(1L, invalidMac, "RRF", "192.168.10.1", "192.168.10.21")));
        }

        assertThat(ip(1)).isEqualTo("192.168.10.1");
        verifyNoInteractions(cache);
    }

    @Test
    void sameAddressAndEmptyChangesAreNoOpsWithoutClearingHealthOrCaches() {
        insertPrinter(1, "192.168.10.1");
        Map<String, Object> before = row(1);

        assertThat(service.apply(List.of(change(1, "192.168.10.1", "192.168.10.1")))).isEmpty();
        assertThat(service.apply(List.of())).isEmpty();
        assertThat(service.apply(null)).isEmpty();

        assertThat(row(1)).isEqualTo(before);
        verifyNoInteractions(cache);
    }

    @Test
    void zeroRowsWhileClearingTheSecondAddressRollsBackTheFirstClear() {
        assertSecondWriteRollback(false, "IGNORE", BusinessException.class);
    }

    @Test
    void zeroRowsWhileSettingTheSecondAddressRollsBackBothClearsAndTheFirstWrite() {
        assertSecondWriteRollback(true, "IGNORE", BusinessException.class);
    }

    @Test
    void sqlFailureWhileSettingTheSecondAddressRollsBackBothDevices() {
        assertSecondWriteRollback(true, "ABORT, 'isolated IP sync failure'", DataAccessException.class);
    }

    @Test
    void identityChangesBetweenTheReadAndWriteAreGuardedAndRolledBack() {
        insertPrinter(1, "192.168.10.1");
        Map<String, Object> before = row(1);
        jdbc.execute("""
                CREATE TRIGGER change_identity
                BEFORE UPDATE OF ip_address ON farm_printer
                WHEN NEW.ip_address IS NULL
                BEGIN
                    UPDATE farm_printer SET mac_address = '00:11:22:33:44:99' WHERE id = OLD.id;
                END
                """);

        assertConflict(List.of(change(1, "192.168.10.1", "192.168.10.21")));

        assertThat(row(1)).isEqualTo(before);
        verifyNoInteractions(cache);
    }

    @Test
    void preservesNewRuntimeFieldsWrittenBetweenReadAndAddressUpdate() {
        insertPrinter(1, "192.168.10.1");
        jdbc.execute("""
                CREATE TRIGGER simulate_runtime_update
                BEFORE UPDATE OF ip_address ON farm_printer
                WHEN NEW.ip_address IS NULL
                BEGIN
                    UPDATE farm_printer SET status = 'PAUSED', current_job_id = 701,
                        is_safe_to_print = 0, last_seen_at = '2026-02-03 04:05:06',
                        last_communication_error = 'fresh detail', consecutive_failure_count = 4,
                        last_known_status = 'PAUSED', last_protocol_state = 'paused', last_device_uptime = 5678
                    WHERE id = OLD.id;
                END
                """);

        service.apply(List.of(change(1, "192.168.10.1", "192.168.10.21")));

        assertThat(row(1)).containsEntry("status", "PAUSED")
                .containsEntry("last_known_status", "PAUSED")
                .containsEntry("last_protocol_state", "paused")
                .containsEntry("last_seen_at", "2026-02-03 04:05:06")
                .containsEntry("last_communication_error", "fresh detail");
        assertThat(((Number) row(1).get("current_job_id")).longValue()).isEqualTo(701);
        assertThat(((Number) row(1).get("is_safe_to_print")).intValue()).isZero();
        assertThat(((Number) row(1).get("consecutive_failure_count")).intValue()).isEqualTo(4);
        assertThat(((Number) row(1).get("last_device_uptime")).longValue()).isEqualTo(5678);
    }

    @Test
    void invalidatesCachesOnlyAfterARealCommitAndBeforeReleasingBothLocks() {
        insertPrinter(1, "192.168.10.1");
        insertPrinter(2, "192.168.10.2");
        doAnswer(invocation -> {
            assertThat(PrinterLock.isHeld(1L)).isTrue();
            assertThat(PrinterLock.isHeld(2L)).isTrue();
            try (var connection = DriverManager.getConnection(dataSource.getJdbcUrl());
                 var statement = connection.createStatement();
                 var results = statement.executeQuery("SELECT ip_address FROM farm_printer ORDER BY id")) {
                assertThat(results.next()).isTrue();
                assertThat(results.getString(1)).isEqualTo("192.168.10.21");
                assertThat(results.next()).isTrue();
                assertThat(results.getString(1)).isEqualTo("192.168.10.22");
            }
            return null;
        }).when(cache).refreshPrinterCache();

        service.apply(List.of(change(1, "192.168.10.1", "192.168.10.21"),
                change(2, "192.168.10.2", "192.168.10.22")));

        verify(cache).refreshPrinterCache();
        assertThat(PrinterLock.isHeld(1L)).isFalse();
        assertThat(PrinterLock.isHeld(2L)).isFalse();
    }

    @Test
    void surroundingTransactionCannotDeferTheCommitBeyondTheDeviceLock() {
        insertPrinter(1, "192.168.10.1");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.apply(List.of(change(1, "192.168.10.1", "192.168.10.21")));
            verify(cache).refreshPrinterCache();
            assertThat(PrinterLock.isHeld(1L)).isFalse();
            status.setRollbackOnly();
        });

        assertThat(ip(1)).isEqualTo("192.168.10.21");
    }

    @Test
    void cacheFailureAfterCommitDoesNotPretendTheAddressWasRolledBack() {
        insertPrinter(1, "192.168.10.1");
        doThrow(new IllegalStateException("isolated cache failure")).when(cache).refreshPrinterCache();

        assertThat(service.apply(List.of(change(1, "192.168.10.1", "192.168.10.21")))).containsExactly(1L);

        assertThat(ip(1)).isEqualTo("192.168.10.21");
        verify(cache).clearStatusCache(1L);
    }

    @Test
    void selectsLockingSqlOnlyOnMySql() throws Exception {
        for (String dialect : new String[] {null, "sqlite", "mysql"}) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setDatabaseId(dialect);
            try (var stream = new ClassPathResource("mapper/PrinterMapper.xml").getInputStream()) {
                new XMLMapperBuilder(stream, configuration, "mapper/PrinterMapper.xml",
                        configuration.getSqlFragments()).parse();
            }
            String sql = configuration.getMappedStatement(
                            PrinterMapper.class.getName() + ".selectAddressSyncPrintersForUpdate")
                    .getBoundSql(Map.of("ids", List.of(1L), "ipAddresses", List.of("192.168.10.21"),
                            "macIdentities", List.of("001122334401"))).getSql();
            assertThat(sql).contains("LOWER(REPLACE(REPLACE(TRIM(mac_address)");
            if ("mysql".equals(dialect)) {
                assertThat(sql).contains("FOR UPDATE");
            } else {
                assertThat(sql).doesNotContain("FOR UPDATE");
            }
        }
    }

    private void assertSecondWriteRollback(boolean finalWrite, String raise, Class<? extends Exception> type) {
        insertPrinter(1, "192.168.10.1");
        insertPrinter(2, "192.168.10.2");
        Map<String, Object> first = row(1);
        Map<String, Object> second = row(2);
        jdbc.execute("""
                CREATE TRIGGER block_address_write
                BEFORE UPDATE OF ip_address ON farm_printer
                WHEN OLD.id = 2 AND NEW.ip_address IS %sNULL
                BEGIN
                    SELECT RAISE(%s);
                END
                """.formatted(finalWrite ? "NOT " : "", raise));

        assertThatThrownBy(() -> service.apply(List.of(change(1, "192.168.10.1", "192.168.10.21"),
                change(2, "192.168.10.2", "192.168.10.22")))).isInstanceOf(type);

        assertThat(row(1)).isEqualTo(first);
        assertThat(row(2)).isEqualTo(second);
        verifyNoInteractions(cache);
    }

    private void assertConflict(List<Change> changes) {
        assertThatThrownBy(() -> service.apply(changes)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(409));
    }

    private void insertPrinter(long id, String ip) {
        jdbc.update("""
                INSERT INTO farm_printer
                    (id, name, ip_address, mac_address, firmware_type, api_key, machine_number,
                     current_material, nozzle_size, grid_row, grid_col, monitor_enabled,
                     status, current_job_id, is_safe_to_print, connection_status, last_seen_at,
                     last_communication_error, consecutive_failure_count, last_known_status,
                     last_protocol_state, last_device_uptime)
                VALUES (?, ?, ?, ?, 'RRF', 'fixture-api-key', ?, 'PLA', 0.4, ?, 1, 1,
                        'PRINTING', ?, 1, 'ONLINE', '2026-01-02 03:04:05',
                        'prior detail', 2, 'PRINTING', 'processing', 1234)
                """, id, "Fixture printer " + id, ip, mac(id), "Fixture-" + id, id, 500L + id);
    }

    private static Change change(long id, String oldIp, String newIp) {
        return new Change(id, mac(id), "RRF", oldIp, newIp);
    }

    private static String mac(long id) {
        return "00:11:22:33:44:%02X".formatted(id);
    }

    private String ip(long id) {
        return jdbc.queryForObject("SELECT ip_address FROM farm_printer WHERE id = ?", String.class, id);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM farm_printer WHERE id = ?", id);
    }

    private Map<String, Object> preservedColumns(long id) {
        Map<String, Object> row = new LinkedHashMap<>(row(id));
        for (String changed : List.of("ip_address", "connection_status", "updated_at")) {
            row.remove(changed);
        }
        return row;
    }

    private static Path createDataDirectory() {
        try {
            Path target = Path.of("target").toAbsolutePath().normalize();
            Files.createDirectories(target);
            return Files.createTempDirectory(target, "printer-address-persistence-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** No app scanning, network sockets, scheduler or live storage access. */
    @Configuration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl("jdbc:sqlite:" + DATA_DIR.resolve("farm.db"));
            source.setDriverClassName("org.sqlite.JDBC");
            source.setMaximumPoolSize(2);
            source.setConnectionTimeout(1000);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/local-schema.sql")).execute(source);
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
        PrinterAddressUpdateService addressUpdateService(PrinterMapper mapper, PrinterCacheService cache,
                                                       PlatformTransactionManager transactionManager) {
            return new PrinterAddressUpdateService(mapper, cache, transactionManager);
        }
    }
}
