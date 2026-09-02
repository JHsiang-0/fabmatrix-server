package com.example.farm.entity.vo;

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

    private Long id;
    private Long fileId;
    private Long printerId;
    private Long userId;
    private Long operatorId;
    private Integer priority;
    private String status;
    private BigDecimal progress;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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
