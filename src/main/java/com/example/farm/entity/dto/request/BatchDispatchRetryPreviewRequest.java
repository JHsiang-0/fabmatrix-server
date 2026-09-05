package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 从批量确认失败结果生成恢复预览的请求。 */
@Data
@Schema(description = "批量失败项恢复预览请求参数")
public class BatchDispatchRetryPreviewRequest {

    @NotBlank(message = "来源计划 ID 不能为空")
    @Size(max = 64, message = "来源计划 ID 长度不能超过 64")
    private String sourcePlanId;

    @NotEmpty(message = "来源计划明细不能为空")
    @Size(max = 100, message = "单次最多恢复 100 个计划明细")
    private List<@NotBlank(message = "来源计划明细 ID 不能为空") @Size(max = 64) String> sourceItemIds;

    @NotBlank(message = "恢复幂等键不能为空")
    @Size(max = 128, message = "恢复幂等键长度不能超过 128")
    private String retryKey;
}
