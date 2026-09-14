package com.example.farm.controller;

import com.example.farm.FarmApplication;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.service.DispatchPlanService;
import com.example.farm.service.PrintJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TC-002 HTTP 路由和实际 SpringDoc 契约测试。 */
@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrintJobControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrintJobService printJobService;

    @MockitoBean
    private DispatchPlanService dispatchPlanService;

    @Test
    void queueGetOnlyInvokesReadPath() throws Exception {
        when(printJobService.getQueuedJobsForCurrentUser()).thenReturn(List.of(new PrintJobSummaryVO()));

        mockMvc.perform(get("/api/v1/print-jobs/queue")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        verify(printJobService).getQueuedJobsForCurrentUser();
        verifyNoMoreInteractions(printJobService);
    }

    @Test
    void apiDocsExposeConcretePrintJobReadAndVoidSchemas() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO").exists())
                .andExpect(jsonPath("$.components.schemas.PrintJobDetailVO").exists())
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.id.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.fileId.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.printerId.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.printerName.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.startedAt.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.completedAt.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobQueryDTO.properties.statuses.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.PrintJobQueryDTO.properties.statuses.maxItems")
                        .value(10))
                .andExpect(jsonPath("$.components.schemas.PrintJobQueryDTO.properties.statuses.minItems")
                        .value(1))
                .andExpect(jsonPath("$.components.schemas.PrintJobQueryDTO.properties.timeField.enum")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("CREATED_AT", "COMPLETED_AT")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.errorReason.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.nozzleSize.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("number", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.fileNameSnapshot.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.retryOfJobId.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.rootJobId.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PrintJobSummaryVO.properties.attemptNumber.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("integer", "null")))
                .andExpect(jsonPath("$.components.schemas.ResultPrintJobSummaryList.properties.data.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.ResultPrintJobSummaryList.properties.data.items.$ref")
                        .value("#/components/schemas/PrintJobSummaryVO"))
                .andExpect(jsonPath("$.components.schemas.ResultPagePrintJobSummary.properties.data.$ref")
                        .value("#/components/schemas/PageResultPrintJobSummary"))
                .andExpect(jsonPath("$.components.schemas.PageResultPrintJobSummary.properties.records.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.PageResultPrintJobSummary.properties.records.items.$ref")
                        .value("#/components/schemas/PrintJobSummaryVO"))
                .andExpect(jsonPath("$.components.schemas.ResultPrintJobDetail.properties.data.$ref")
                        .value("#/components/schemas/PrintJobDetailVO"))
                .andExpect(jsonPath("$.components.schemas.ResultVoid.properties.data.type").value("null"))
                .andExpect(jsonPath("$.paths['/api/v1/print-jobs/queue'].get.responses['200'].content.*.schema.$ref")
                        .value("#/components/schemas/ResultPrintJobSummaryList"))
                .andExpect(jsonPath("$.paths['/api/v1/print-jobs/page'].post.responses['200'].content.*.schema.$ref")
                        .value("#/components/schemas/ResultPagePrintJobSummary"))
                .andExpect(jsonPath("$.paths['/api/v1/print-jobs/{id}'].get.responses['200'].content.*.schema.$ref")
                        .value("#/components/schemas/ResultPrintJobDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/print-jobs/safe/assign'].post.responses['200'].content.*.schema.$ref")
                        .value("#/components/schemas/ResultVoid"))
                .andExpect(jsonPath("$.paths['/api/v1/print-jobs/safe/start'].post.responses['200'].content.*.schema.$ref")
                        .value("#/components/schemas/ResultVoid"))
                .andExpect(jsonPath("$.paths['/api/v1/print-jobs/safe/confirm'].post.responses['200'].content.*.schema.$ref")
                        .value("#/components/schemas/ResultVoid"))
                .andExpect(jsonPath("$.paths['/api/v1/print-jobs/{id}/reconcile'].post.responses['200'].content.*.schema.$ref")
                        .value("#/components/schemas/ResultVoid"));
    }

    @Test
    void pageRejectsExplicitEmptyStatusesBeforeInvokingService() throws Exception {
        String emptyStatuses = "{\"pageNum\":1,\"pageSize\":10,\"statuses\":[]}";
        mockMvc.perform(post("/api/v1/print-jobs/page")
                        .with(user("operator").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyStatuses))
                .andExpect(status().isBadRequest());

        String mixedEmptyStatuses = "{\"pageNum\":1,\"pageSize\":10,\"status\":\"COMPLETED\",\"statuses\":[]}";
        mockMvc.perform(post("/api/v1/print-jobs/page")
                        .with(user("operator").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mixedEmptyStatuses))
                .andExpect(status().isBadRequest());

        verifyNoMoreInteractions(printJobService);
    }

    @Test
    void legacyAssignReturnsGoneWithoutCallingTaskService() throws Exception {
        mockMvc.perform(post("/api/v1/print-jobs/100/assign")
                        .with(user("operator").roles("OPERATOR"))
                        .param("printerId", "200"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(410));

        verifyNoMoreInteractions(printJobService);
    }

    @Test
    void legacyAssignReturnsGoneForMissingOrMalformedPrinterIdWithoutCallingTaskService() throws Exception {
        mockMvc.perform(post("/api/v1/print-jobs/100/assign")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(410));

        mockMvc.perform(post("/api/v1/print-jobs/100/assign")
                        .with(user("operator").roles("OPERATOR"))
                        .param("printerId", "not-a-long"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(410));

        verifyNoMoreInteractions(printJobService);
    }

    @Test
    void batchDispatchEndpointsReturnStableFeatureDisabledEnvelopeByDefault() throws Exception {
        String preview = "{\"fileIds\":[20],\"printerIds\":[404],\"strategy\":\"ONE_TO_ONE\",\"action\":\"QUEUE\"}";
        String confirm = "{\"planId\":\"plan-1\",\"version\":1,\"itemIds\":[\"item-1\"],\"confirmationToken\":\"token-1\"}";
        String retry = "{\"sourcePlanId\":\"plan-1\",\"sourceItemIds\":[\"item-1\"],\"retryKey\":\"retry-1\"}";

        for (var request : List.of(
                buildPost("/api/v1/print-jobs/batch/preview", preview),
                buildPost("/api/v1/print-jobs/batch/confirm", confirm),
                buildPost("/api/v1/print-jobs/batch/retry-preview", retry))) {
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(10004))
                    .andExpect(jsonPath("$.message").value("功能暂未开放"))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }

        verifyNoInteractions(dispatchPlanService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder buildPost(
            String path, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .with(user("operator").roles("OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
