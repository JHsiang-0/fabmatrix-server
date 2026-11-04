package com.example.farm.local;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.FarmApplication;
import com.example.farm.common.storage.FileStorage;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterScanResultDTO;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.PrintFileService;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrinterModelService;
import com.example.farm.service.CanonicalPrinterStateService;
import com.example.farm.protocol.observation.PrinterObservation;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local Edition 的 SQLite 方言、本地文件存储和基础 Mapper 烟囱测试。 */
@SpringBootTest(classes = FarmApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "farm.websocket.enabled=false",
        "farm.monitor.enabled=false",
        "farm.cloud.enabled=false",
        "farm.discovery.enabled=false",
        "spring.datasource.hikari.connection-timeout=1000"
})
class LocalEditionMapperTest {

    private static final Path DATA_DIR = Path.of("target/local-edition-test-data");

    @DynamicPropertySource
    static void localDataDirectory(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(DATA_DIR);
        registry.add("FARM_DATA_DIR", () -> DATA_DIR.toString());
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PrintFileMapper printFileMapper;
    @Autowired
    private PrinterMapper printerMapper;
    @Autowired
    private PrinterService printerService;
    @Autowired
    private CanonicalPrinterStateService canonicalPrinterStateService;
    @Autowired
    private PrinterModelService printerModelService;
    @Autowired
    private PrintJobMapper printJobMapper;
    @Autowired
    private FileStorage fileStorage;
    @Autowired
    private com.example.farm.config.LocalSchemaMigration localSchemaMigration;
    @Autowired
    private PrintFileService printFileService;
    @Autowired
    private PrintJobService printJobService;
    @Autowired
    private HikariDataSource dataSource;

    @BeforeEach
    void cleanBusinessRows() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM farm_audit_log");
        jdbcTemplate.update("DELETE FROM farm_print_job");
        jdbcTemplate.update("DELETE FROM farm_print_file");
        jdbcTemplate.update("DELETE FROM farm_printer");
    }

    @AfterAll
    static void removeTestDatabase() throws Exception {
        if (Files.exists(DATA_DIR)) {
            try (var paths = Files.walk(DATA_DIR)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // 测试进程仍持有 SQLite 文件时，允许构建目录清理器稍后回收。
                    }
                });
            }
        }
    }

    @Test
    void sqliteCustomMapperUsesPortableSearchAndUpsert() {
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (user_id, is_folder, original_name, safe_name, file_size, material_type)
                VALUES (7, 0, 'Cube-PLA.gcode', 'cube.gcode', 12, 'PLA')
                """);

        Page<PrintFile> page = printFileMapper.selectFilePage(
                new Page<>(1, 10), 7L, false, null, "Cube", "PLA", null, false);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).singleElement()
                .extracting(PrintFile::getOriginalName).isEqualTo("Cube-PLA.gcode");

        Printer printer = new Printer();
        String deviceUid = UUID.randomUUID().toString();
        printer.setDeviceUid(deviceUid);
        printer.setName("RRF-01");
        printer.setIpAddress("192.168.0.77");
        printer.setMacAddress("aa:bb:cc:dd:ee:ff");
        printer.setFirmwareType("RRF");
        printer.setStatus("IDLE");
        printer.setMonitorEnabled(true);
        printerMapper.upsertByMacAddress(printer);

        assertThat(printerMapper.selectByMacAddress("aa:bb:cc:dd:ee:ff").getMonitorEnabled()).isTrue();
        assertThat(printerMapper.selectByMacAddress(printer.getMacAddress()).getDeviceUid()).isEqualTo(deviceUid);
        jdbcTemplate.update("UPDATE farm_printer SET monitor_enabled=0 WHERE mac_address=?", printer.getMacAddress());
        printer.setDeviceUid(UUID.randomUUID().toString());
        printer.setIpAddress("198.51.100.21");
        printerMapper.upsertByMacAddress(printer);
        assertThat(printerMapper.selectByMacAddress(printer.getMacAddress()).getMonitorEnabled()).isFalse();
        assertThat(printerMapper.selectByMacAddress(printer.getMacAddress()).getDeviceUid()).isEqualTo(deviceUid);
    }

    @Test
    void sqliteBatchUpsertPersistsDistinctIdentitiesAndKeepsExistingIdentity() {
        Printer first = identityCandidate("02:00:00:00:00:31", "198.51.100.31");
        Printer second = identityCandidate("02:00:00:00:00:32", "198.51.100.32");
        String originalUid = first.getDeviceUid();
        printerMapper.batchUpsert(List.of(first, second));
        assertThat(printerMapper.selectByMacAddress(first.getMacAddress()).getDeviceUid()).isEqualTo(originalUid);
        assertThat(printerMapper.selectByMacAddress(second.getMacAddress()).getDeviceUid())
                .isEqualTo(second.getDeviceUid()).isNotEqualTo(originalUid);

        jdbcTemplate.update("UPDATE farm_printer SET current_job_id=7001, is_safe_to_print=1, monitor_enabled=1 "
                + "WHERE mac_address=?", first.getMacAddress());
        first.setDeviceUid(UUID.randomUUID().toString());
        first.setIpAddress("198.51.100.33");
        printerMapper.batchUpsert(List.of(first));
        Printer stored = printerMapper.selectByMacAddress(first.getMacAddress());
        assertThat(stored.getDeviceUid()).isEqualTo(originalUid);
        assertThat(stored.getIpAddress()).isEqualTo("198.51.100.33");
        assertThat(stored.getCurrentJobId()).isEqualTo(7001L);
        assertThat(stored.getIsSafeToPrint()).isTrue();
        assertThat(stored.getMonitorEnabled()).isTrue();
    }

    @Test
    void existingStartupMigrationFillsOnlyMissingIdentitiesAndIsIdempotent() {
        Printer stable = identityCandidate("02:00:00:00:00:51", "198.51.100.51");
        printerMapper.insert(stable);
        String stableUid = stable.getDeviceUid();
        jdbcTemplate.update("""
                INSERT INTO farm_printer (name, mac_address, device_uid, firmware_type, status,
                    current_job_id, is_safe_to_print, monitor_enabled)
                VALUES ('Missing null', '02:00:00:00:00:52', NULL, 'RRF', 'PRINTING', 7002, 1, 1),
                       ('Missing blank', '02:00:00:00:00:53', ' ', 'KLIPPER', 'PRINTING', 7003, 1, 1)
                """);
        localSchemaMigration.run();
        Printer repaired = printerMapper.selectByMacAddress("02:00:00:00:00:52");
        String repairedUid = repaired.getDeviceUid();
        assertThat(UUID.fromString(repairedUid).toString()).isEqualTo(repairedUid);
        assertThat(repaired.getStatus()).isEqualTo("PRINTING");
        assertThat(repaired.getCurrentJobId()).isEqualTo(7002L);
        assertThat(repaired.getIsSafeToPrint()).isTrue();
        assertThat(repaired.getMonitorEnabled()).isTrue();
        String otherUid = printerMapper.selectByMacAddress("02:00:00:00:00:53").getDeviceUid();
        assertThat(UUID.fromString(otherUid).toString()).isEqualTo(otherUid);
        assertThat(otherUid).isNotEqualTo(repairedUid);
        localSchemaMigration.run();
        assertThat(printerMapper.selectByMacAddress(stable.getMacAddress()).getDeviceUid()).isEqualTo(stableUid);
        assertThat(printerMapper.selectByMacAddress(repaired.getMacAddress()).getDeviceUid()).isEqualTo(repairedUid);
        assertThat(printerMapper.selectByMacAddress("02:00:00:00:00:53").getDeviceUid()).isEqualTo(otherUid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RRF", "KLIPPER"})
    void scannedPrinterIdentityReachesPersistedModelAndClientReadApi(String firmware) {
        var scan = PrinterScanResultDTO.of("198.51.100.41", "02:00:00:00:00:41", true);
        scan.setFirmwareType(firmware);
        scan.setSuggestedName("Identity fixture");
        var result = printerService.batchUpsertPrinters(List.of(scan));
        assertThat(result.getInsertedCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isZero();
        Printer stored = printerMapper.selectByMacAddress("02:00:00:00:00:41");
        assertThat(stored.getDeviceUid()).isNotBlank();
        assertThat(UUID.fromString(stored.getDeviceUid()).toString()).isEqualTo(stored.getDeviceUid());

        stored.setConnectionStatus("ONLINE");
        stored.setLastSeenAt(LocalDateTime.now());
        stored.setStatus("PRINTING");
        printerMapper.updateById(stored);
        Instant at = Instant.now();
        var temperature = new PrinterObservation.Measurement(new BigDecimal("205.0"),
                PrinterObservation.Quality.GOOD, at);
        var target = new PrinterObservation.Measurement(new BigDecimal("210.0"),
                PrinterObservation.Quality.GOOD, at);
        var heater = new PrinterObservation.Heater("tool", PrinterObservation.HeaterRole.TOOL,
                PrinterObservation.HeaterMode.ACTIVE, temperature, target, target, null);
        var observation = new PrinterObservation(at, at, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.PRINTING,
                List.of(heater), List.of(new PrinterObservation.Tool("tool", List.of("tool"))), "tool", null);
        assertThat(canonicalPrinterStateService.persist(stored, observation)).isTrue();
        var response = printerModelService.getModel(stored.getId());
        assertThat(response.stale()).isFalse();
        Map<?, ?> identity = (Map<?, ?>) response.model().get("identity");
        assertThat(identity.get("deviceUid")).isEqualTo(stored.getDeviceUid());
        Map<?, ?> modelObservation = (Map<?, ?>) response.model().get("observation");
        assertThat(modelObservation.get("operationState")).isEqualTo("PRINTING");
        Map<?, ?> modelHeater = (Map<?, ?>) ((List<?>) modelObservation.get("heaters")).getFirst();
        Map<?, ?> currentTemperature = (Map<?, ?>) modelHeater.get("currentC");
        assertThat(((Number) currentTemperature.get("value")).doubleValue()).isEqualTo(205.0);
    }

    @Test
    void localFileStorageDoesNotExposeDiskPath() throws Exception {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "cube.gcode", "text/plain", "G1 X1\n".getBytes());

        String objectUrl = fileStorage.uploadFile("uploads/cube.gcode", upload);

        assertThat(objectUrl).isEqualTo("local://uploads/cube.gcode");
        assertThat(fileStorage.getPresignedUrl("uploads/cube.gcode", java.time.Duration.ofMinutes(1)))
                .startsWith("/api/v1/print-files/storage?key=")
                .doesNotContain(DATA_DIR.toString());
        Resource resource = fileStorage.getFileStream("uploads/cube.gcode");
        assertThat(resource.getInputStream().readAllBytes()).isEqualTo("G1 X1\n".getBytes());

        fileStorage.deleteFile("uploads/cube.gcode");
        assertThat(Files.exists(DATA_DIR.resolve("files/uploads/cube.gcode"))).isFalse();
    }

    @Test
    void sqlitePrintJobMapperWritesAssignRootAndRetryTimesInAsiaShanghai() {
        ensureUser(41, "mapper-time-user");
        jdbcTemplate.update("INSERT INTO farm_printer(id, name, machine_number, status) "
                + "VALUES (4401, 'Time Printer', 'TIME-01', 'IDLE')");
        jdbcTemplate.update("INSERT INTO farm_print_job(id, user_id, status, created_at, updated_at) "
                + "VALUES (4400, 41, 'QUEUED', '2000-01-01 00:00:00', '2000-01-01 00:00:00'), "
                + "(4401, 41, 'QUEUED', '2000-01-01 00:00:00', '2000-01-01 00:00:00'), "
                + "(4402, 41, 'FAILED', '2000-01-01 00:00:00', '2000-01-01 00:00:00')");
        jdbcTemplate.update("UPDATE farm_print_job SET root_job_id = id, attempt_number = 1 WHERE id = 4402");

        assertThat(printJobMapper.initializeRootJobId(4400L)).isEqualTo(1);
        assertThat(printJobMapper.assignJobIfQueued(4401L, 4401L, "Time Printer", "TIME-01"))
                .isEqualTo(1);

        PrintJob retry = new PrintJob();
        retry.setUserId(41L);
        retry.setRetryOfJobId(4402L);
        retry.setRootJobId(4402L);
        retry.setAttemptNumber(2);
        retry.setPriority(0);
        retry.setProgress(BigDecimal.ZERO);
        assertThat(printJobMapper.insertRetryIfAbsent(retry)).isEqualTo(1);

        Map<String, Object> offsets = jdbcTemplate.queryForMap("""
                SELECT
                    CAST(strftime('%s', (SELECT updated_at FROM farm_print_job WHERE id = 4400))
                        - strftime('%s', 'now') AS INTEGER) AS root_offset,
                    CAST(strftime('%s', (SELECT updated_at FROM farm_print_job WHERE id = 4401))
                        - strftime('%s', 'now') AS INTEGER) AS assign_offset,
                    CAST(strftime('%s', (SELECT created_at FROM farm_print_job WHERE id = ?))
                        - strftime('%s', 'now') AS INTEGER) AS retry_created_offset,
                    CAST(strftime('%s', (SELECT updated_at FROM farm_print_job WHERE id = ?))
                        - strftime('%s', 'now') AS INTEGER) AS retry_updated_offset
                """, retry.getId(), retry.getId());
        assertThat(offsets.values()).allSatisfy(value ->
                assertThat(((Number) value).longValue()).isBetween(28795L, 28805L));
    }

    @Test
    void retryRejectionsKeepStableCodesAndAuditWithSingleConnection() {
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(1);
        ensureUser(51, "retry-audit-user");
        jdbcTemplate.update("INSERT INTO farm_print_job(id, user_id, status, root_job_id, attempt_number) "
                + "VALUES (4500, 51, 'READY', 4500, 1), (4501, 51, 'FAILED', 4501, 1), "
                + "(4502, 51, 'QUEUED', 9999, 2)");
        jdbcTemplate.update("UPDATE farm_print_job SET retry_of_job_id = 4501 WHERE id = 4502");
        mockOperator(51L);

        assertThatThrownBy(() -> printJobService.retryJob(4599L))
                .extracting("code").isEqualTo(404L);
        assertThatThrownBy(() -> printJobService.retryJob(4500L))
                .extracting("code").isEqualTo(422L);
        assertThatThrownBy(() -> printJobService.retryJob(4501L))
                .extracting("code").isEqualTo(409L);

        assertThat(jdbcTemplate.queryForList("""
                SELECT error_code FROM farm_audit_log
                WHERE action = 'JOB_RETRY_REJECT'
                ORDER BY id
                """, String.class)).containsExactly(
                        "JOB_NOT_FOUND", "JOB_NOT_FAILED", "RETRY_LINEAGE_CONFLICT");
    }

    @Test
    void keepsPublicRootFilesAtRootAcrossRepeatableMigration() {
        ensureUser(1, "migration-user");
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (id, parent_id, is_folder, is_default_folder, visibility,
                     original_name, safe_name, user_id)
                VALUES (1000, NULL, 1, 1, 'PRIVATE', 'migration-user的文件', 'folder_default_1', 1)
                """);
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (id, parent_id, is_folder, is_default_folder, visibility,
                     original_name, safe_name, user_id)
                VALUES (1001, NULL, 0, 0, 'PRIVATE', 'private-root.gcode', 'private-root.gcode', 1)
                """);
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (id, parent_id, is_folder, is_default_folder, visibility,
                     original_name, safe_name, user_id)
                VALUES (1002, NULL, 0, 0, 'PUBLIC', 'public-root.gcode', 'public-root.gcode', 1)
                """);

        localSchemaMigration.run();
        localSchemaMigration.run();

        assertThat(parentIdOf(1001)).isEqualTo(1000);
        assertThat(parentIdOf(1002)).isNull();
        assertThat(visibilityOf(1002)).isEqualTo("PUBLIC");
    }

    @Test
    void acceptsSameNamedPrivateDefaultFoldersForDifferentUsers() {
        ensureUser(1, "private-user-a");
        ensureUser(2, "private-user-b");
        insertDefaultFolder(2000, 1, "同名私有目录", "PRIVATE");
        insertDefaultFolder(2001, 2, "同名私有目录", "PRIVATE");

        localSchemaMigration.run();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM farm_print_file
                WHERE is_folder = 1 AND parent_id IS NULL AND original_name = '同名私有目录'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void enforcesPublicRootNamespaceButAllowsPrivateRootNamesPerOwner() {
        ensureUser(1, "public-admin-a");
        ensureUser(2, "public-admin-b");
        mockAdmin(1L);

        printFileService.createFolder(null, "共享根目录", "PUBLIC");

        mockAdmin(2L);
        assertThatThrownBy(() -> printFileService.createFolder(null, "共享根目录", "PUBLIC"))
                .hasMessage("该目录下已存在同名文件夹")
                .extracting("code")
                .isEqualTo(409L);

        printFileService.createFolder(null, "各自私有根", "PRIVATE");
        mockAdmin(1L);
        printFileService.createFolder(null, "各自私有根", "PRIVATE");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM farm_print_file
                WHERE is_folder = 1 AND parent_id IS NULL AND original_name = '各自私有根'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void blocksParentFileFixtureDuringMigration() {
        ensureUser(1, "non-folder-parent-user");
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (id, parent_id, is_folder, is_default_folder, visibility,
                     original_name, safe_name, user_id)
                VALUES (3000, NULL, 0, 0, 'PRIVATE', 'parent-file.gcode', 'parent-file.gcode', 1)
                """);
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (id, parent_id, is_folder, is_default_folder, visibility,
                     original_name, safe_name, user_id)
                VALUES (3001, 3000, 0, 0, 'PRIVATE', 'child-file.gcode', 'child-file.gcode', 1)
                """);

        assertThatThrownBy(() -> localSchemaMigration.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("阻止启动");
    }

    private Printer identityCandidate(String macAddress, String ipAddress) {
        Printer printer = new Printer();
        printer.setDeviceUid(UUID.randomUUID().toString());
        printer.setName("Identity fixture");
        printer.setMacAddress(macAddress);
        printer.setIpAddress(ipAddress);
        printer.setFirmwareType("RRF");
        printer.setStatus("IDLE");
        return printer;
    }

    private void ensureUser(int id, String username) {
        jdbcTemplate.update("""
                INSERT OR IGNORE INTO farm_user (id, username, password_hash, role, enabled)
                VALUES (?, ?, 'test-hash', 'OPERATOR', 1)
                """, id, username);
    }

    private void insertDefaultFolder(int id, int userId, String name, String visibility) {
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (id, parent_id, is_folder, is_default_folder, visibility,
                     original_name, safe_name, user_id)
                VALUES (?, NULL, 1, 1, ?, ?, ?, ?)
                """, id, visibility, name, "folder-" + id, userId);
    }

    private Integer parentIdOf(int fileId) {
        return jdbcTemplate.queryForObject(
                "SELECT parent_id FROM farm_print_file WHERE id = ?", Integer.class, fileId);
    }

    private String visibilityOf(int fileId) {
        return jdbcTemplate.queryForObject(
                "SELECT visibility FROM farm_print_file WHERE id = ?", String.class, fileId);
    }

    private void mockAdmin(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private void mockOperator(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "retry-audit-user",
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }
}
