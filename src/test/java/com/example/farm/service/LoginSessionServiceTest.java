package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LoginSessionServiceTest {
    private JdbcTemplate jdbc;
    private LoginSessionService service;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE farm_user (id BIGINT PRIMARY KEY,username VARCHAR(30),enabled INT,token_version BIGINT)");
        jdbc.execute("INSERT INTO farm_user VALUES (1,'worker',1,0),(2,'other',1,0)");
        jdbc.execute("CREATE TABLE farm_login_session (id VARCHAR(36) PRIMARY KEY,user_id BIGINT,token_version BIGINT,ip_address VARCHAR(64),user_agent VARCHAR(256),created_at TIMESTAMP,last_seen_at TIMESTAMP,expires_at TIMESTAMP,revoked_at TIMESTAMP)");
        service = new LoginSessionService(jdbc, mock(AuditLogService.class));
        ReflectionTestUtils.setField(service, "expireTime", 60_000L);
    }

    private String login() {
        User user = new User(); user.setId(1L);
        return service.create(user);
    }

    @Test void revokesOneSessionWithoutAffectingAnotherAndRejectsWrongOwner() {
        String first = login(), second = login();
        assertThat(service.valid(first, 1L, 0L)).isTrue();
        assertThat(service.valid(first, 2L, 0L)).isFalse();
        assertThatThrownBy(() -> service.revoke(2L, first)).isInstanceOf(BusinessException.class);
        service.revoke(1L, first);
        assertThat(service.valid(first, 1L, 0L)).isFalse();
        assertThat(service.valid(second, 1L, 0L)).isTrue();
        assertThat(service.list(1L, second)).singleElement().satisfies(row -> assertThat(row.current()).isTrue());
    }

    @Test void revokeOthersKeepsCurrentAndVersionChangesHideOldSessions() {
        String first = login(), second = login();
        service.revokeAll(1L, first);
        assertThat(service.valid(first, 1L, 0L)).isTrue();
        assertThat(service.valid(second, 1L, 0L)).isFalse();
        assertThat(service.valid(first, 1L, 1L)).isFalse();
        jdbc.update("UPDATE farm_user SET token_version=1 WHERE id=1");
        assertThat(service.list(1L, first)).isEmpty();
    }

    @Test void expiredOrMissingSessionCannotAuthorize() {
        String id = login();
        jdbc.update("UPDATE farm_login_session SET expires_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP)");
        assertThat(service.valid(id, 1L, 0L)).isFalse();
        assertThat(service.valid(null, 1L, 0L)).isFalse();
        assertThat(service.list(1L, id)).isEmpty();
    }

    @Test void lastSeenUpdatesAreThrottled() {
        String id = login();
        var before = service.list(1L, id).getFirst().lastSeenAt();
        assertThat(service.valid(id, 1L, 0L)).isTrue();
        assertThat(service.list(1L, id).getFirst().lastSeenAt()).isEqualTo(before);
        jdbc.update("UPDATE farm_login_session SET last_seen_at=DATEADD('MINUTE',-6,CURRENT_TIMESTAMP)");
        service.valid(id, 1L, 0L);
        assertThat(service.list(1L, id).getFirst().lastSeenAt()).isAfterOrEqualTo(before);
    }
}
