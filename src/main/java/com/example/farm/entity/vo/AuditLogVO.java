package com.example.farm.entity.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.example.farm.entity.AuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 对外审计日志视图，只暴露安全元数据。 */
@Data
@Schema(name = "AuditLogVO", description = "操作日志安全视图")
public class AuditLogVO {

    @JsonSerialize(using = ToStringSerializer.class) private Long id;
    @JsonSerialize(using = ToStringSerializer.class) private Long actorId;
    private String actorUsername;
    private String actorRole;
    private String action;
    private String targetType;
    private String targetId;
    private String targetLabel;
    private String result;
    private String errorCode;
    private LocalDateTime occurredAt;
    private String traceId;

    public static AuditLogVO from(AuditLog source) {
        if (source == null) {
            return null;
        }
        AuditLogVO target = new AuditLogVO();
        target.id = source.getId();
        target.actorId = source.getActorId();
        target.actorUsername = source.getActorUsername();
        target.actorRole = source.getActorRole();
        target.action = source.getAction();
        target.targetType = source.getTargetType();
        target.targetId = source.getTargetId();
        target.targetLabel = source.getTargetLabel();
        target.result = source.getResult();
        target.errorCode = source.getErrorCode();
        target.occurredAt = source.getOccurredAt();
        target.traceId = source.getTraceId();
        return target;
    }
}
