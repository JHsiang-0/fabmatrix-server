package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 布局过道的内部 JSON 传输结构；保存请求将在 SYNC-002.3 扩展。 */
@Data
@Schema(name = "DashboardAisleDTO", description = "动态看板过道定义")
public class DashboardAisleDTO {

    @Schema(description = "过道稳定标识", example = "default-column-aisle")
    @NotBlank(message = "过道 ID 不能为空")
    @Size(max = 64, message = "过道 ID 不能超过 64 个字符")
    private String id;

    @Schema(description = "过道方向：ROW 或 COLUMN", requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"ROW", "COLUMN"})
    @NotBlank(message = "过道方向不能为空")
    @Pattern(regexp = "(?i)^(ROW|COLUMN)$", message = "过道方向只能是 ROW 或 COLUMN")
    private String axis;

    @Schema(description = "插入到第几个物理行/列之后，0 表示最前方", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "过道位置不能为空")
    @Min(value = 0, message = "过道位置不能小于 0")
    private Integer afterIndex;

    @Schema(description = "过道显示名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "过道名称不能为空")
    @Size(max = 12, message = "过道名称不能超过 12 个字符")
    private String label;

    @Schema(description = "过道视觉尺寸，单位 CSS px", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "过道尺寸不能为空")
    @Min(value = 48, message = "过道尺寸不能小于 48")
    @Max(value = 240, message = "过道尺寸不能超过 240")
    private Integer size;
}
