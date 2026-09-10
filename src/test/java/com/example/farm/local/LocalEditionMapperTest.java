package com.example.farm.local;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.FarmApplication;
import com.example.farm.common.storage.FileStorage;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.PrintFileService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local Edition 的 SQLite 方言、本地文件存储和基础 Mapper 烟囱测试。 */
@SpringBootTest(classes = FarmApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "FARM_DATA_DIR=target/local-edition-test-data",
        "farm.websocket.enabled=false"
})
class LocalEditionMapperTest {

    private static final Path DATA_DIR = Path.of("target/local-edition-test-data");

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PrintFileMapper printFileMapper;
    @Autowired
    private PrinterMapper printerMapper;
    @Autowired
    private FileStorage fileStorage;
    @Autowired
    private com.example.farm.config.LocalSchemaMigration localSchemaMigration;
    @Autowired
    private PrintFileService printFileService;

    @BeforeEach
    void cleanBusinessRows() {
        SecurityContextHolder.clearContext();
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
        printer.setName("RRF-01");
        printer.setIpAddress("192.168.0.77");
        printer.setMacAddress("aa:bb:cc:dd:ee:ff");
        printer.setFirmwareType("RRF");
        printer.setStatus("IDLE");
        printerMapper.upsertByMacAddress(printer);

        assertThat(printerMapper.selectByMacAddress("aa:bb:cc:dd:ee:ff")).isNotNull();
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
}
