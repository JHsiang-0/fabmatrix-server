package com.example.farm.local;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.FarmApplication;
import com.example.farm.common.storage.FileStorage;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrinterMapper;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    @BeforeEach
    void cleanBusinessRows() {
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
                new Page<>(1, 10), 7L, false, null, "Cube", "PLA");

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
}
