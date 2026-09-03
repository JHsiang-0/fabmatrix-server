package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 批量分配计划确认请求。 */
@Data
@Schema(description = "批量分配计划确认请求参数")
public class BatchDispatchConfirmRequest {

    @Schema(description = "服务端生成的计划 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "计划 ID 不能为空")
    @Size(max = 64, message = "计划 ID 长度不能超过 64")
    private String planId;

    @Schema(description = "预览计划版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "计划版本不能为空")
    @Positive(message = "计划版本必须为正数")
    private Long version;

    @Schema(description = "本次确认执行的计划明细 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "计划明细不能为空")
    @Size(max = 100, message = "单次最多确认 100 个计划明细")
    private List<@NotBlank(message = "计划明细 ID 不能为空") @Size(max = 64, message = "计划明细 ID 长度不能超过 64") String> itemIds;

    @Schema(description = "一次性确认令牌", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "确认令牌不能为空")
    @Size(max = 256, message = "确认令牌长度不能超过 256")
    private String confirmationToken;
}
