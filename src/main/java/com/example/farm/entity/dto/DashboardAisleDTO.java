package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 布局过道的内部 JSON 传输结构；保存请求将在 SYNC-002.3 扩展。 */
@Data
@Schema(name = "DashboardAisleDTO", description = "动态看板过道定义")
public class DashboardAisleDTO {

    @Schema(description = "过道稳定标识", example = "default-column-aisle")
    private String id;

    @Schema(description = "过道方向：ROW 或 COLUMN", allowableValues = {"ROW", "COLUMN"})
    private String axis;

    @Schema(description = "插入到第几个物理行/列之后，0 表示最前方")
    private Integer afterIndex;

    @Schema(description = "过道显示名称")
    private String label;

    @Schema(description = "过道视觉尺寸，单位 CSS px")
    private Integer size;
}
