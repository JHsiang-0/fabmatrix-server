package com.example.farm.entity.vo;

import com.example.farm.entity.DispatchPlanItem;
import lombok.Data;

/** 批量计划逐项响应。 */
@Data
public class DispatchPlanItemVO {
    private String itemId;
    private Long fileId;
    private String fileName;
    private Long printerId;
    private String printerName;
    private String printerStatus;
    private Boolean canExecute;
    private String status;
    private String reasonCode;
    private String message;
    private Long jobId;
    private Integer attemptCount;
    private Boolean retryable;

    public static DispatchPlanItemVO from(DispatchPlanItem item) {
        DispatchPlanItemVO vo = new DispatchPlanItemVO();
        vo.itemId = item.getId();
        vo.fileId = item.getFileId();
        vo.printerId = item.getPrinterId();
        vo.status = item.getStatus();
        vo.reasonCode = item.getReasonCode();
        vo.message = item.getMessage();
        vo.jobId = item.getJobId();
        vo.attemptCount = item.getAttemptCount();
        vo.retryable = "RETRYABLE".equals(item.getStatus());
        vo.canExecute = "PENDING".equals(item.getStatus()) && item.getPrinterId() != null
                && item.getReasonCode() == null;
        return vo;
    }
}
