package com.example.farm.entity.vo;

import com.example.farm.entity.dto.DashboardAisleDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 对外返回的规范化过道定义。 */
@Data
@Schema(name = "DashboardAisleVO", description = "动态看板过道响应对象")
public class DashboardAisleVO {

    @Schema(description = "过道稳定标识", example = "default-column-aisle")
    private String id;

    @Schema(description = "过道方向：ROW 或 COLUMN", example = "COLUMN")
    private String axis;

    @Schema(description = "插入到第几个物理行/列之后")
    private Integer afterIndex;

    @Schema(description = "过道显示名称", example = "过道")
    private String label;

    @Schema(description = "过道视觉尺寸，单位 CSS px", example = "100")
    private Integer size;

    public static DashboardAisleVO from(DashboardAisleDTO source) {
        DashboardAisleVO target = new DashboardAisleVO();
        target.id = source.getId();
        target.axis = source.getAxis();
        target.afterIndex = source.getAfterIndex();
        target.label = source.getLabel();
        target.size = source.getSize();
        return target;
    }
}
