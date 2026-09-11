package com.example.farm.entity.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.example.farm.entity.PrintJob;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打印任务安全响应对象。
 */
@Data
@Schema(name = "PrintJobVO", description = "打印任务安全响应对象")
public class PrintJobVO implements Serializable {

    @JsonSerialize(using = ToStringSerializer.class) @Schema(description = "任务 ID") private Long id;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(description = "切片文件 ID") private Long fileId;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(description = "绑定打印机 ID；排队任务为 null") private Long printerId;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(description = "发起任务的用户 ID") private Long userId;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(description = "现场操作员用户 ID") private Long operatorId;
    @Schema(description = "任务优先级，范围 0-100") private Integer priority;
    @Schema(description = "任务状态：QUEUED、ASSIGNED、UPLOADING、READY、PRINTING、PAUSED、RECONCILING、COMPLETED、FAILED、CANCELLED") private String status;
    @Schema(description = "打印进度，范围 0-100") private BigDecimal progress;
    @Schema(description = "开始时间") private LocalDateTime startedAt;
    @Schema(description = "完成时间") private LocalDateTime completedAt;
    @Schema(description = "失败或核对原因；无原因时为 null") private String errorReason;
    @Schema(description = "创建时间") private LocalDateTime createdAt;
    @Schema(description = "更新时间") private LocalDateTime updatedAt;

    public static PrintJobVO from(PrintJob job) {
        if (job == null) {
            return null;
        }
        PrintJobVO vo = new PrintJobVO();
        vo.id = job.getId();
        vo.fileId = job.getFileId();
        vo.printerId = job.getPrinterId();
        vo.userId = job.getUserId();
        vo.operatorId = job.getOperatorId();
        vo.priority = job.getPriority();
        vo.status = job.getStatus();
        vo.progress = job.getProgress();
        vo.startedAt = job.getStartedAt();
        vo.completedAt = job.getCompletedAt();
        vo.errorReason = job.getErrorReason();
        vo.createdAt = job.getCreatedAt();
        vo.updatedAt = job.getUpdatedAt();
        return vo;
    }
}
