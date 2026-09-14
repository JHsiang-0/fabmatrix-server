package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.config.BatchDispatchProperties;
import com.example.farm.entity.dto.request.BatchDispatchConfirmRequest;
import com.example.farm.entity.dto.request.BatchDispatchPreviewRequest;
import com.example.farm.entity.dto.request.BatchDispatchRetryPreviewRequest;
import com.example.farm.entity.vo.BatchDispatchConfirmVO;
import com.example.farm.entity.vo.DispatchPlanPreviewVO;
import com.example.farm.service.DispatchPlanService;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 功能开关开启时保留既有三个批量派发入口的 Controller 契约。 */
@ExtendWith(MockitoExtension.class)
class PrintJobControllerFeatureEnabledTest {

    @Mock
    private PrintJobService printJobService;
    @Mock
    private PrinterService printerService;
    @Mock
    private DispatchPlanService dispatchPlanService;

    @Test
    void enabledFeatureDelegatesAllBatchEndpointsToExistingService() {
        var properties = new BatchDispatchProperties();
        properties.setEnabled(true);
        var controller = new PrintJobController(printJobService, printerService, dispatchPlanService, properties);

        var preview = new DispatchPlanPreviewVO();
        var confirmation = new BatchDispatchConfirmVO();
        var retryPreview = new DispatchPlanPreviewVO();
        when(dispatchPlanService.preview(any())).thenReturn(preview);
        when(dispatchPlanService.confirm(any())).thenReturn(confirmation);
        when(dispatchPlanService.retryPreview(any())).thenReturn(retryPreview);

        Result<DispatchPlanPreviewVO> previewResult = controller.previewBatch(previewRequest());
        Result<BatchDispatchConfirmVO> confirmResult = controller.confirmBatch(confirmRequest());
        Result<DispatchPlanPreviewVO> retryResult = controller.retryPreviewBatch(retryRequest());

        assertThat(previewResult.getData()).isSameAs(preview);
        assertThat(confirmResult.getData()).isSameAs(confirmation);
        assertThat(retryResult.getData()).isSameAs(retryPreview);
        verify(dispatchPlanService).preview(any(BatchDispatchPreviewRequest.class));
        verify(dispatchPlanService).confirm(any(BatchDispatchConfirmRequest.class));
        verify(dispatchPlanService).retryPreview(any(BatchDispatchRetryPreviewRequest.class));
    }

    private BatchDispatchPreviewRequest previewRequest() {
        var request = new BatchDispatchPreviewRequest();
        request.setFileIds(List.of(20L));
        request.setPrinterIds(List.of(404L));
        request.setStrategy("ONE_TO_ONE");
        request.setAction("QUEUE");
        return request;
    }

    private BatchDispatchConfirmRequest confirmRequest() {
        var request = new BatchDispatchConfirmRequest();
        request.setPlanId("plan-1");
        request.setVersion(1L);
        request.setItemIds(List.of("item-1"));
        request.setConfirmationToken("token-1");
        return request;
    }

    private BatchDispatchRetryPreviewRequest retryRequest() {
        var request = new BatchDispatchRetryPreviewRequest();
        request.setSourcePlanId("plan-1");
        request.setSourceItemIds(List.of("item-1"));
        request.setRetryKey("retry-1");
        return request;
    }
}
