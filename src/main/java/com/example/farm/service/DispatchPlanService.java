package com.example.farm.service;

import com.example.farm.entity.dto.request.BatchDispatchConfirmRequest;
import com.example.farm.entity.dto.request.BatchDispatchPreviewRequest;
import com.example.farm.entity.vo.BatchDispatchConfirmVO;
import com.example.farm.entity.vo.DispatchPlanPreviewVO;

/** 用户主动发起的批量分配计划服务。 */
public interface DispatchPlanService {
    DispatchPlanPreviewVO preview(BatchDispatchPreviewRequest request);

    BatchDispatchConfirmVO confirm(BatchDispatchConfirmRequest request);
}
