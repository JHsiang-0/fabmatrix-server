package com.example.farm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.FarmApplication;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.service.PrintFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper SQL 集成测试。
 *
 * <p>使用 H2 的 MySQL 模式验证筛选、权限条件和分页 SQL；真实 MySQL
 * 方言、索引执行计划和 Docker 数据卷迁移仍需在现场环境验证。</p>
 */
@SpringBootTest(classes = FarmApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PrintFileMapperTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PrintFileMapper printFileMapper;

    @Autowired
    private PrintJobMapper printJobMapper;

    @Autowired
    private PrinterMapper printerMapper;

    @Autowired
    private PrintFileService printFileService;

    @BeforeEach
    void createTables() {
        dropTables();
        jdbcTemplate.execute("""
                CREATE TABLE farm_print_file (
                    id BIGINT PRIMARY KEY,
                    parent_id BIGINT,
                    is_folder BOOLEAN NOT NULL,
                    rustfs_key VARCHAR(255),
                    original_name VARCHAR(255) NOT NULL,
                    safe_name VARCHAR(255) NOT NULL,
                    file_url VARCHAR(500),
                    file_size BIGINT,
                    user_id BIGINT,
                    created_at TIMESTAMP NOT NULL,
                    est_time INT,
                    material_type VARCHAR(20),
                    nozzle_size DECIMAL(3, 2),
                    thumbnail_url VARCHAR(500),
                    filament_weight DECIMAL(10, 2),
                    filament_length DECIMAL(10, 2),
                    nozzle_temp INT,
                    bed_temp INT,
                    layer_height DECIMAL(10, 2),
                    first_layer_nozzle_temp INT,
                    first_layer_bed_temp INT,
                    first_layer_height DECIMAL(10, 2)
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
                    updated_at TIMESTAMP NOT NULL,
                    file_url VARCHAR(500),
                    est_time INT,
                    material_type VARCHAR(20),
                    nozzle_size DECIMAL(3, 2)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE farm_printer (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(50) NOT NULL,
                    ip_address VARCHAR(50),
                    mac_address VARCHAR(50),
                    firmware_type VARCHAR(20) NOT NULL,
                    api_key VARCHAR(255),
                    status VARCHAR(20) NOT NULL,
                    is_safe_to_print BOOLEAN NOT NULL DEFAULT FALSE,
                    current_job_id BIGINT,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);

        insertFile(1L, 7L, "Cube-PLA.gcode", "PLA", "2026-09-01 10:00:00");
        insertFile(2L, 8L, "Cube-PLA-other.gcode", "PLA", "2026-09-01 11:00:00");
        insertFile(3L, 7L, "Cube-PETG.gcode", "PETG", "2026-09-01 12:00:00");

        jdbcTemplate.update("""
                INSERT INTO farm_print_job
                    (id, file_id, user_id, priority, status, progress, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, 11L, 1L, 7L, 10, "QUEUED", BigDecimal.ZERO,
                "2026-09-01 10:01:00", "2026-09-01 10:01:00");
        jdbcTemplate.update("""
                INSERT INTO farm_print_job
                    (id, file_id, user_id, priority, status, progress, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, 12L, 1L, 8L, 20, "COMPLETED", BigDecimal.valueOf(100),
                "2026-09-01 10:02:00", "2026-09-01 10:02:00");
    }

    @AfterEach
    void dropTablesAfterTest() {
        SecurityContextHolder.clearContext();
        dropTables();
    }

    @Test
    void folderContentReturnsFoldersBeforeFiles() {
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (id, is_folder, original_name, safe_name, file_size, user_id, created_at, material_type)
                VALUES (?, TRUE, ?, ?, NULL, ?, ?, NULL)
                """, 4L, "Models", "Models", 7L, "2026-09-01 13:00:00");
        mockOperator(7L);

        List<PrintFile> contents = printFileService.getFolderContent(null);

        assertThat(contents).extracting(PrintFile::getId)
                .containsExactly(4L, 3L, 1L);
    }

    @Test
    void operatorFilePageAppliesOwnerNameMaterialAndPagination() {
        Page<PrintFile> page = printFileMapper.selectFilePage(
                new Page<>(1, 1), 7L, false, null, "Cube", "PLA");

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).singleElement()
                .extracting(PrintFile::getId, PrintFile::getUserId, PrintFile::getMaterialType)
                .containsExactly(1L, 7L, "PLA");
    }

    @Test
    void adminTreeAndFileJobPageRespectAdminAndOperatorBoundaries() {
        List<PrintFile> operatorTree = printFileMapper.selectAccessibleFileTree(7L, false);
        assertThat(operatorTree).extracting(PrintFile::getId).containsExactly(3L, 1L);

        List<PrintFile> adminTree = printFileMapper.selectAccessibleFileTree(null, true);
        assertThat(adminTree).extracting(PrintFile::getId).containsExactly(3L, 2L, 1L);

        Page<PrintJob> operatorJobs = printJobMapper.selectPageByFileId(
                new Page<>(1, 10), 1L, 7L, false);
        assertThat(operatorJobs.getTotal()).isEqualTo(1);
        assertThat(operatorJobs.getRecords()).singleElement()
                .extracting(PrintJob::getId, PrintJob::getUserId)
                .containsExactly(11L, 7L);

        Page<PrintJob> adminJobs = printJobMapper.selectPageByFileId(
                new Page<>(1, 10), 1L, null, true);
        assertThat(adminJobs.getTotal()).isEqualTo(2);
        assertThat(adminJobs.getRecords()).extracting(PrintJob::getId)
                .containsExactly(12L, 11L);
    }

    @Test
    void printerBindingUpdateIsAtomicAndRejectsSecondJob() {
        jdbcTemplate.update("INSERT INTO farm_printer (id, name, firmware_type, status, current_job_id) VALUES (1, 'P1', 'RRF', 'IDLE', NULL)");

        assertThat(printerMapper.bindJobIfIdle(1L, 101L)).isEqualTo(1);
        assertThat(printerMapper.bindJobIfIdle(1L, 102L)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT current_job_id FROM farm_printer WHERE id=1", Long.class))
                .isEqualTo(101L);
    }

    @Test
    void printJobCountSupportsUserAndStatusFilters() {
        assertThat(printFileMapper.countPrintJobsByFileId(1L, null, null)).isEqualTo(2);
        assertThat(printFileMapper.countPrintJobsByFileId(1L, 7L, null)).isEqualTo(1);
        assertThat(printFileMapper.countPrintJobsByFileId(1L, null, "COMPLETED")).isEqualTo(1);
        assertThat(printFileMapper.countPrintJobsByFileId(1L, 7L, "COMPLETED")).isZero();
    }

    private void insertFile(Long id, Long userId, String originalName,
                            String materialType, String createdAt) {
        jdbcTemplate.update("""
                INSERT INTO farm_print_file
                    (id, is_folder, original_name, safe_name, file_size, user_id, created_at, material_type)
                VALUES (?, FALSE, ?, ?, ?, ?, ?, ?)
                """, id, originalName, originalName, 1024L, userId, createdAt, materialType);
    }

    private void dropTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS farm_printer");
        jdbcTemplate.execute("DROP TABLE IF EXISTS farm_print_job");
        jdbcTemplate.execute("DROP TABLE IF EXISTS farm_print_file");
    }

    private void mockOperator(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }
}
