package com.example.farm.entity.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.example.farm.entity.DispatchPlanItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 批量计划逐项响应。 */
@Data
@Schema(name = "DispatchPlanItemVO", description = "批量派发计划单项结果")
public class DispatchPlanItemVO {
    @Schema(description = "计划明细 ID")
    private String itemId;
    @Schema(description = "文件 ID")
    @JsonSerialize(using = ToStringSerializer.class) private Long fileId;
    @Schema(description = "文件名称")
    private String fileName;
    @Schema(description = "打印机 ID")
    @JsonSerialize(using = ToStringSerializer.class) private Long printerId;
    @Schema(description = "打印机名称")
    private String printerName;
    @Schema(description = "打印机当前状态")
    private String printerStatus;
    @Schema(description = "当前明细是否可以执行")
    private Boolean canExecute;
    @Schema(description = "明细状态")
    private String status;
    @Schema(description = "稳定原因码")
    private String reasonCode;
    @Schema(description = "面向用户的原因说明")
    private String message;
    @Schema(description = "已创建任务 ID；未创建时为 null")
    @JsonSerialize(using = ToStringSerializer.class) private Long jobId;
    @Schema(description = "来源计划 ID；恢复项使用")
    private String sourcePlanId;
    @Schema(description = "来源明细 ID；恢复项使用")
    private String sourceItemId;
    @Schema(description = "恢复动作提示")
    private String recoveryAction;
    @Schema(description = "当前明细尝试次数")
    private Integer attemptCount;
    @Schema(description = "是否允许恢复；仅无任务 ID 的可恢复失败项为 true")
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
        vo.sourcePlanId = item.getSourcePlanId();
        vo.sourceItemId = item.getSourceItemId();
        vo.recoveryAction = item.getRecoveryAction();
        vo.attemptCount = item.getAttemptCount();
        vo.retryable = "RETRYABLE".equals(item.getStatus()) && item.getJobId() == null;
        vo.canExecute = "PENDING".equals(item.getStatus()) && item.getPrinterId() != null
                && item.getReasonCode() == null;
        return vo;
    }
}
