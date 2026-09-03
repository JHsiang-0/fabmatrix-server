package com.example.farm.entity.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 批量确认逐项执行结果。 */
@Data
public class BatchDispatchConfirmVO {
    private String planId;
    private String status;
    private Boolean repeated;
    private List<DispatchPlanItemVO> items = new ArrayList<>();
}
