package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 仅保存会话元数据，不保存 JWT；数据库为撤销状态真源。 */
@Service
@RequiredArgsConstructor
public class LoginSessionService {
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;
    @Value("${jwt.expire-time:604800000}")
    private long expireTime;

    public record SessionView(String id, String username, String ipAddress, String userAgent,
                              LocalDateTime createdAt, LocalDateTime lastSeenAt,
                              LocalDateTime expiresAt, boolean current) { }

    @Transactional
    public String create(User user) {
        String id = UUID.randomUUID().toString();
        String ip = null;
        String agent = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            ip = attrs.getRequest().getRemoteAddr(); // 不信任客户端提交的代理头。
            agent = attrs.getRequest().getHeader("User-Agent");
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO farm_login_session (id,user_id,token_version,ip_address,user_agent,created_at,last_seen_at,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                id, user.getId(), user.getTokenVersion() == null ? 0L : user.getTokenVersion(),
                truncate(ip, 64), truncate(agent, 256), Timestamp.valueOf(now), Timestamp.valueOf(now),
                Timestamp.valueOf(now.plusNanos(expireTime * 1_000_000)));
        return id;
    }

    public boolean valid(String id, Long userId, Long version) {
        if (id == null || userId == null) return false;
        LocalDateTime now = LocalDateTime.now();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM farm_login_session WHERE id=? AND user_id=? AND token_version=? AND revoked_at IS NULL AND expires_at>?",
                Integer.class, id, userId, version == null ? 0L : version, Timestamp.valueOf(now));
        if (count == null || count != 1) return false;
        jdbc.update("UPDATE farm_login_session SET last_seen_at=? WHERE id=? AND revoked_at IS NULL AND last_seen_at<?",
                Timestamp.valueOf(now), id, Timestamp.valueOf(now.minusMinutes(5)));
        return true;
    }

    public List<SessionView> list(Long userId, String currentId) {
        return jdbc.query("""
                SELECT s.*,u.username FROM farm_login_session s JOIN farm_user u ON u.id=s.user_id
                WHERE s.user_id=? AND s.revoked_at IS NULL AND s.expires_at>?
                  AND s.token_version=COALESCE(u.token_version,0) AND u.enabled=1
                ORDER BY s.created_at DESC,s.id LIMIT 100
                """, (rs, n) -> new SessionView(rs.getString("id"), rs.getString("username"),
                rs.getString("ip_address"), rs.getString("user_agent"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("last_seen_at").toLocalDateTime(),
                rs.getTimestamp("expires_at").toLocalDateTime(), rs.getString("id").equals(currentId)),
                userId, Timestamp.valueOf(LocalDateTime.now()));
    }

    @Transactional
    public void revoke(Long userId, String id) {
        int count = jdbc.update("UPDATE farm_login_session SET revoked_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), id, userId);
        if (count == 0) throw new BusinessException(404, "登录会话不存在或已经退出");
        audit.recordSuccess("SESSION_REVOKE", "USER", userId, "撤销单个登录会话");
        closeAfterCommit(userId, id, null);
    }

    @Transactional
    public void revokeAll(Long userId, String exceptId) {
        if (exceptId == null) {
            jdbc.update("UPDATE farm_login_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
                    Timestamp.valueOf(LocalDateTime.now()), userId);
        } else {
            jdbc.update("UPDATE farm_login_session SET revoked_at=? WHERE user_id=? AND id<>? AND revoked_at IS NULL",
                    Timestamp.valueOf(LocalDateTime.now()), userId, exceptId);
        }
        audit.recordSuccess("SESSION_REVOKE_ALL", "USER", userId,
                exceptId == null ? "撤销全部登录会话" : "撤销其他登录会话");
        closeAfterCommit(userId, null, exceptId);
    }

    private void closeAfterCommit(Long userId, String id, String exceptId) {
        Runnable close = () -> com.example.farm.controller.WebSocketServer.revokeLoginSessions(userId, id, exceptId);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { close.run(); }
                    });
        } else close.run();
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(max, value.length()));
    }
}
