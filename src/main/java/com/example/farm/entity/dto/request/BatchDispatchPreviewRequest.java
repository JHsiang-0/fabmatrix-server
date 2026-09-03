package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户发起的批量分配预览请求。
 *
 * <p>预览接口只能计算方案，不能创建任务、锁定打印机或调用设备写操作。</p>
 */
@Data
@Schema(description = "批量分配预览请求参数")
public class BatchDispatchPreviewRequest {

    @Schema(description = "文件 ID 列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "文件 ID 列表不能为空")
    @Size(max = 100, message = "单次最多选择 100 个文件")
    private List<@NotNull(message = "文件 ID 不能为空") @Positive(message = "文件 ID 必须为正数") Long> fileIds;

    @Schema(description = "打印机 ID 列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "打印机 ID 列表不能为空")
    @Size(max = 100, message = "单次最多选择 100 台打印机")
    private List<@NotNull(message = "打印机 ID 不能为空") @Positive(message = "打印机 ID 必须为正数") Long> printerIds;

    @Schema(description = "匹配策略: ONE_TO_ONE、ROUND_ROBIN、AUTO_MATCH", example = "AUTO_MATCH")
    @NotNull(message = "匹配策略不能为空")
    @Pattern(regexp = "(?i)^(ONE_TO_ONE|ROUND_ROBIN|AUTO_MATCH)$",
            message = "strategy 只能是 ONE_TO_ONE、ROUND_ROBIN 或 AUTO_MATCH")
    private String strategy;

    @Schema(description = "执行动作: UPLOAD_ONLY、QUEUE、START_AFTER_CONFIRM", example = "UPLOAD_ONLY")
    @Pattern(regexp = "(?i)^(UPLOAD_ONLY|QUEUE|START_AFTER_CONFIRM)$",
            message = "action 只能是 UPLOAD_ONLY、QUEUE 或 START_AFTER_CONFIRM")
    private String action = "UPLOAD_ONLY";
}
