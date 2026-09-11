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
@Schema(name = "PrintJobVO", description = "当前已实现的打印任务安全响应对象；TC-001 目标读模型为 PrintJobSummaryVO/PrintJobDetailVO")
public class PrintJobVO implements Serializable {

    @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", format = "int64", description = "任务 ID；JSON 十进制字符串") private Long id;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", format = "int64", description = "切片文件 ID；JSON 十进制字符串") private Long fileId;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", format = "int64", description = "绑定打印机 ID；JSON 十进制字符串；排队任务为 null") private Long printerId;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", format = "int64", description = "发起任务的用户 ID；JSON 十进制字符串") private Long userId;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string", format = "int64", description = "现场操作员用户 ID；JSON 十进制字符串") private Long operatorId;
    @Schema(description = "任务优先级，范围 0-100") private Integer priority;
    @Schema(description = "任务状态：QUEUED、ASSIGNED、UPLOADING、READY、PRINTING、PAUSED、RECONCILING、COMPLETED、FAILED、CANCELLED；PENDING/MANUAL/CANCELED 仅为遗留存储值，不属于 GET 目标集合") private String status;
    @Schema(description = "打印进度，范围 0-100") private BigDecimal progress;
    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss", description = "开始时间；ISO-8601 local datetime") private LocalDateTime startedAt;
    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss", description = "完成时间；ISO-8601 local datetime") private LocalDateTime completedAt;
    @Schema(description = "失败或核对原因；无原因时为 null") private String errorReason;
    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss", description = "创建时间；ISO-8601 local datetime") private LocalDateTime createdAt;
    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss", description = "更新时间；ISO-8601 local datetime") private LocalDateTime updatedAt;

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
