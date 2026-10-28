package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.entity.AuditLog;
import com.example.farm.entity.dto.AuditLogQueryDTO;
import com.example.farm.entity.vo.AuditLogVO;
import com.example.farm.mapper.AuditLogMapper;
import com.example.farm.service.AuditLogService;
import com.example.farm.common.utils.LogUtil;
import com.example.farm.common.utils.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/** 审计日志实现。写入字段均由后端业务服务构造，避免把请求体落库。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl extends ServiceImpl<AuditLogMapper, AuditLog> implements AuditLogService {

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";
    private static final int MAX_LABEL_LENGTH = 200;

    private final AuditLogMapper auditLogMapper;

    @Override
    public IPage<AuditLogVO> pageAuditLogs(AuditLogQueryDTO query) {
        AuditLogQueryDTO normalized = query == null ? new AuditLogQueryDTO() : query;
        Page<AuditLogVO> page = new Page<>(normalized.getPageNum(), normalized.getPageSize());
        return auditLogMapper.selectAuditLogPage(page, normalized);
    }

    @Override
    @Transactional
    public void recordSuccess(String action, String targetType, Object targetId, String targetLabel) {
        Actor actor = currentActor();
        insert(actor.actorId(), actor.username(), actor.role(), action, targetType, targetId,
                targetLabel, SUCCESS, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String action, String targetType, Object targetId, String targetLabel,
                              String errorCode) {
        try {
            Actor actor = currentActor();
            insert(actor.actorId(), actor.username(), actor.role(), action, targetType, targetId,
                    targetLabel, FAILURE, errorCode);
        } catch (RuntimeException exception) {
            // 失败留痕不能覆盖原始业务错误；成功事件仍由所在事务保证一致性。
            log.warn("审计失败事件写入失败: action={}, targetType={}, errorCode={}",
                    action, targetType, errorCode, exception);
        }
    }

    @Override
    @Transactional
    public void recordLoginSuccess(Long actorId, String actorUsername, String actorRole) {
        insert(new Actor(actorId, safeText(actorUsername, 50), safeText(actorRole, 20)),
                "LOGIN", "USER", actorId, actorUsername, SUCCESS, null);
    }

    @Override
    // 登录拒绝会提交失败审计；复用登录事务，避免 SQLite 单连接池内申请第二个连接。
    @Transactional
    public void recordLoginFailure(String actorUsername, Long targetId, String errorCode) {
        try {
            insert(new Actor(null, safeText(actorUsername, 50), null),
                    "LOGIN", "USER", targetId, actorUsername, FAILURE, errorCode);
        } catch (RuntimeException exception) {
            log.warn("登录失败审计写入失败: errorCode={}", errorCode, exception);
        }
    }

    private Actor currentActor() {
        Long actorId = SecurityContextUtil.getCurrentUserIdNullable();
        String username = safeText(SecurityContextUtil.getCurrentUsername(), 50);
        String role = safeText(SecurityContextUtil.getCurrentRole(), 20);
        return new Actor(actorId, username, role);
    }

    private void insert(Actor actor, String action, String targetType, Object targetId,
                        String targetLabel, String result, String errorCode) {
        insert(actor.actorId(), actor.username(), actor.role(), action, targetType, targetId,
                targetLabel, result, errorCode);
    }

    private void insert(Long actorId, String actorUsername, String actorRole, String action,
                        String targetType, Object targetId, String targetLabel, String result,
                        String errorCode) {
        if (!StringUtils.hasText(action) || !StringUtils.hasText(targetType)) {
            throw new IllegalArgumentException("审计动作和目标类型不能为空");
        }
        AuditLog logRecord = new AuditLog();
        logRecord.setActorId(actorId);
        logRecord.setActorUsername(safeText(actorUsername, 50));
        logRecord.setActorRole(safeText(actorRole, 20));
        logRecord.setAction(safeText(action, 64));
        logRecord.setTargetType(safeText(targetType, 32));
        logRecord.setTargetId(targetId == null ? null : safeText(String.valueOf(targetId), 64));
        logRecord.setTargetLabel(safeText(targetLabel, MAX_LABEL_LENGTH));
        logRecord.setResult(result);
        logRecord.setErrorCode(safeText(errorCode, 32));
        logRecord.setOccurredAt(LocalDateTime.now());
        logRecord.setTraceId(safeText(MDC.get("traceId"), 64));
        if (auditLogMapper.insert(logRecord) != 1) {
            throw new IllegalStateException("审计日志保存失败");
        }
    }

    private String safeText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }

    private record Actor(Long actorId, String username, String role) {
    }
}
