package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 结构化操作审计记录。仅保存可供管理员检索的安全元数据，不保存请求/响应内容或凭据。
 */
@Data
@TableName("farm_audit_log")
@Schema(name = "AuditLog", description = "操作审计日志内部实体")
public class AuditLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("actor_id")
    private Long actorId;

    @TableField("actor_username")
    private String actorUsername;

    @TableField("actor_role")
    private String actorRole;

    private String action;

    @TableField("target_type")
    private String targetType;

    @TableField("target_id")
    private String targetId;

    @TableField("target_label")
    private String targetLabel;

    private String result;

    @TableField("error_code")
    private String errorCode;

    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    @TableField("trace_id")
    private String traceId;
}
