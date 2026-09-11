package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 打印任务与排队调度表
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Getter
@Setter
@ToString
@TableName("farm_print_job")
@Schema(name = "FarmPrintJob", description = "打印任务与排队调度表")
public class PrintJob implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务流水号
     */
    @Schema(description = "任务流水号")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联的切片文件 ID
     */
    @TableField("file_id")
    @Schema(description = "关联的切片文件 ID")
    private Long fileId;

    /**
     * 分配的打印机 ID（排队中为 NULL）
     */
    @TableField("printer_id")
    @Schema(description = "分配的打印机 ID（排队中为 NULL）")
    private Long printerId;

    /**
     * 发起任务的用户 ID
     */
    @TableField("user_id")
    @Schema(description = "发起任务的用户 ID")
    private Long userId;

    /** 创建时保存的文件名称，不随文件改名或删除变化。 */
    @TableField("file_name_snapshot")
    @Schema(description = "创建时的文件名快照")
    private String fileNameSnapshot;

    /** 最近一次合法派发时保存的打印机名称。 */
    @TableField("printer_name_snapshot")
    @Schema(description = "派发时的打印机名称快照")
    private String printerNameSnapshot;

    /** 最近一次合法派发时保存的机器编号。 */
    @TableField("machine_number_snapshot")
    @Schema(description = "派发时的机器编号快照")
    private String machineNumberSnapshot;

    /** 创建时保存的用户名，不随用户改名变化。 */
    @TableField("creator_username_snapshot")
    @Schema(description = "创建时的用户名快照")
    private String creatorUsernameSnapshot;

    /** 真正进入 PRINTING 时保存的现场操作员用户名。 */
    @TableField("operator_username_snapshot")
    @Schema(description = "真正启动打印时的操作员用户名快照")
    private String operatorUsernameSnapshot;

    /** 客户端创建请求幂等键；同一用户下重复键返回首次创建的任务。 */
    @TableField("idempotency_key")
    private String idempotencyKey;

    /** 直接来源任务；仅重试任务设置。 */
    @TableField("retry_of_job_id")
    @Schema(description = "直接来源任务 ID")
    private Long retryOfJobId;

    /** 首次任务 ID；根任务自身指向自身。 */
    @TableField("root_job_id")
    @Schema(description = "首次任务 ID")
    private Long rootJobId;

    /** 当前任务在同一根任务链中的尝试序号。 */
    @TableField("attempt_number")
    @Schema(description = "任务尝试序号")
    private Integer attemptNumber;

    /**
     * 现场操作员 ID（确认安全、启动打印时记录）
     */
    @TableField("operator_id")
    @Schema(description = "现场操作员ID（确认安全、启动打印时记录）")
    private Long operatorId;

    /**
     * 排队优先级（数值越高越优先）
     */
    @TableField("priority")
    @Schema(description = "排队优先级（数值越高越优先）")
    private Integer priority;

    /**
     * 任务状态：QUEUED, ASSIGNED, READY, PRINTING, PAUSED, COMPLETED, FAILED, CANCELLED
     */
    @TableField("status")
    @Schema(description = "任务状态：QUEUED, ASSIGNED, READY, PRINTING, PAUSED, COMPLETED, FAILED, CANCELLED")
    private String status;

    /**
     * 打印进度（0.00 - 100.00）
     */
    @TableField("progress")
    @Schema(description = "打印进度（0.00 - 100.00）")
    private BigDecimal progress;

    /**
     * 实际开始打印时间
     */
    @TableField("started_at")
    @Schema(description = "实际开始打印时间")
    private LocalDateTime startedAt;

    /**
     * 实际完成/失败时间
     */
    @TableField("completed_at")
    @Schema(description = "实际完成/失败时间")
    private LocalDateTime completedAt;

    /**
     * 失败原因（炒面、断料等）
     */
    @TableField("error_reason")
    @Schema(description = "失败原因（炒面、断料等）")
    private String errorReason;

    /**
     * 任务创建时间
     */
    @TableField("created_at")
    @Schema(description = "任务创建时间")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
