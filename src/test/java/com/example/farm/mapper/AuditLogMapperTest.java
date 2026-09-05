package com.example.farm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.FarmApplication;
import com.example.farm.entity.dto.AuditLogQueryDTO;
import com.example.farm.entity.vo.AuditLogVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = FarmApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AuditLogMapperTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @BeforeEach
    void createTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS farm_audit_log (
                    id BIGINT PRIMARY KEY,
                    actor_id BIGINT,
                    actor_username VARCHAR(100),
                    actor_role VARCHAR(30),
                    action VARCHAR(100) NOT NULL,
                    target_type VARCHAR(50),
                    target_id VARCHAR(100),
                    target_label VARCHAR(255),
                    result VARCHAR(20) NOT NULL,
                    error_code VARCHAR(100),
                    occurred_at TIMESTAMP NOT NULL,
                    trace_id VARCHAR(100)
                )
                """);
        jdbcTemplate.execute("DELETE FROM farm_audit_log");
        jdbcTemplate.update("""
                INSERT INTO farm_audit_log
                    (id, actor_id, actor_username, actor_role, action, target_type, target_id,
                     target_label, result, error_code, occurred_at, trace_id)
                VALUES (1, 7, 'operator', 'OPERATOR', 'JOB_CREATE', 'JOB', '10',
                        '任务 A', 'SUCCESS', NULL, '2026-09-01 10:00:00', 'trace-1')
                """);
        jdbcTemplate.update("""
                INSERT INTO farm_audit_log
                    (id, actor_id, actor_username, actor_role, action, target_type, target_id,
                     target_label, result, error_code, occurred_at, trace_id)
                VALUES (2, 1, 'admin', 'ADMIN', 'JOB_CANCEL', 'JOB', '11',
                        '任务 B', 'SUCCESS', NULL, '2026-09-01 11:00:00', 'trace-2')
                """);
        jdbcTemplate.update("""
                INSERT INTO farm_audit_log
                    (id, actor_id, actor_username, actor_role, action, target_type, target_id,
                     target_label, result, error_code, occurred_at, trace_id)
                VALUES (3, 1, 'admin', 'ADMIN', 'JOB_CANCEL', 'JOB', '12',
                        '任务 C', 'FAILURE', 'NOT_READY', '2026-09-01 11:00:00', 'trace-3')
                """);
    }

    @AfterEach
    void clearTable() {
        jdbcTemplate.execute("DELETE FROM farm_audit_log");
    }

    @Test
    void filtersAndSortsWithStableIdTieBreaker() {
        AuditLogQueryDTO query = new AuditLogQueryDTO();
        query.setAction("JOB_CANCEL");
        query.setResult("SUCCESS");

        Page<AuditLogVO> page = auditLogMapper.selectAuditLogPage(new Page<>(1, 1), query);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).singleElement()
                .extracting(AuditLogVO::getId, AuditLogVO::getActorUsername,
                        AuditLogVO::getAction, AuditLogVO::getResult)
                .containsExactly(2L, "admin", "JOB_CANCEL", "SUCCESS");
    }

    @Test
    void ordersEqualTimestampsByDescendingIdAndExposesOnlySafeFields() {
        Page<AuditLogVO> page = auditLogMapper.selectAuditLogPage(
                new Page<>(1, 10), new AuditLogQueryDTO());

        assertThat(page.getRecords()).extracting(AuditLogVO::getId).containsExactly(3L, 2L, 1L);
        assertThat(AuditLogVO.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("password", "token", "requestBody", "responseBody", "stackTrace");
    }
}
