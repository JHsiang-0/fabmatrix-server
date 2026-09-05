package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 批量确认逐项执行结果。 */
@Data
@Schema(name = "BatchDispatchConfirmVO", description = "批量派发确认结果")
public class BatchDispatchConfirmVO {
    @Schema(description = "计划 ID")
    private String planId;
    @Schema(description = "计划最终状态")
    private String status;
    @Schema(description = "是否为重复确认请求")
    private Boolean repeated;
    @Schema(description = "逐项执行结果")
    private List<DispatchPlanItemVO> items = new ArrayList<>();
}
