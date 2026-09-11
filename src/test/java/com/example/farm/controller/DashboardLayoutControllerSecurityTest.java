package com.example.farm.controller;

import com.example.farm.FarmApplication;
import com.example.farm.common.exception.DashboardLayoutProblemException;
import com.example.farm.entity.vo.DashboardLayoutLimitsVO;
import com.example.farm.entity.vo.DashboardLayoutVO;
import com.example.farm.entity.vo.DashboardLayoutSaveResultVO;
import com.example.farm.entity.vo.DashboardLayoutProblemVO;
import com.example.farm.service.DashboardLayoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardLayoutControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardLayoutService dashboardLayoutService;

    @Test
    void adminAndOperatorCanReadTheSameResponseShape() throws Exception {
        DashboardLayoutVO layout = new DashboardLayoutVO();
        layout.setRowCount(4);
        layout.setColumnCount(12);
        layout.setAisles(List.of());
        layout.setVersion(1L);
        layout.setLimits(DashboardLayoutLimitsVO.defaults());
        when(dashboardLayoutService.getLayout()).thenReturn(layout);

        for (String role : new String[]{"ADMIN", "OPERATOR"}) {
            mockMvc.perform(get("/api/v1/dashboard-layout").with(user(role.toLowerCase()).roles(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.rowCount").value(4))
                    .andExpect(jsonPath("$.data.columnCount").value(12))
                    .andExpect(jsonPath("$.data.version").value(1))
                    .andExpect(jsonPath("$.data.limits.maxCells").value(1800))
                    .andExpect(jsonPath("$.data.aisles").isArray());
        }
    }

    @Test
    void anonymousReadReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard-layout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void operatorCannotSaveLayout() throws Exception {
        mockMvc.perform(put("/api/v1/dashboard-layout")
                        .with(user("operator").roles("OPERATOR"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanSaveLayout() throws Exception {
        when(dashboardLayoutService.saveLayout(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DashboardLayoutSaveResultVO());

        mockMvc.perform(put("/api/v1/dashboard-layout")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void structuredSaveConflictUsesUnifiedResponse() throws Exception {
        when(dashboardLayoutService.saveLayout(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DashboardLayoutProblemException(409, "看板布局保存失败: VERSION_CONFLICT",
                        new DashboardLayoutProblemVO("VERSION_CONFLICT", 2L, List.of(), List.of())));

        mockMvc.perform(put("/api/v1/dashboard-layout")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.reason").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.serverVersion").value(2));
    }

    @Test
    void malformedJsonReturnsStructuredLayoutProblem() throws Exception {
        mockMvc.perform(put("/api/v1/dashboard-layout")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data.reason").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data.conflicts").isArray())
                .andExpect(jsonPath("$.data.outOfBoundsPrinters").isArray());
    }

    @Test
    void beanValidationReturnsStructuredLayoutProblem() throws Exception {
        mockMvc.perform(put("/api/v1/dashboard-layout")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"rowCount\":4,\"columnCount\":12,\"aisles\":[],"
                                + "\"overflowPolicy\":\"REJECT\",\"positionChanges\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data.reason").value("INVALID_REQUEST"));
    }

    @Test
    void structuredNotFoundAndShrinkConflictKeepProblemData() throws Exception {
        org.mockito.Mockito.doThrow(new DashboardLayoutProblemException(404, "看板布局保存失败: PRINTER_NOT_FOUND",
                        new DashboardLayoutProblemVO("PRINTER_NOT_FOUND", 1L, List.of(404L), List.of())))
                .when(dashboardLayoutService).saveLayout(org.mockito.ArgumentMatchers.any());
        mockMvc.perform(put("/api/v1/dashboard-layout")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.reason").value("PRINTER_NOT_FOUND"))
                .andExpect(jsonPath("$.data.conflicts[0]").value(404));

        org.mockito.Mockito.doThrow(new DashboardLayoutProblemException(422, "看板布局保存失败: OUT_OF_BOUNDS",
                        new DashboardLayoutProblemVO("OUT_OF_BOUNDS", 1L, List.of(), List.of())))
                .when(dashboardLayoutService).saveLayout(org.mockito.ArgumentMatchers.any());
        mockMvc.perform(put("/api/v1/dashboard-layout")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.reason").value("OUT_OF_BOUNDS"));
    }

    @Test
    void apiDocsDescribeNamedLayoutProblemEnvelope() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ResultDashboardLayoutProblem.properties.code").exists())
                .andExpect(jsonPath("$.components.schemas.ResultDashboardLayoutProblem.properties.code.description")
                        .value(org.hamcrest.Matchers.containsString("400、404、409 或 422")))
                .andExpect(jsonPath("$.components.schemas.ResultDashboardLayoutProblem.properties.message").exists())
                .andExpect(jsonPath("$.components.schemas.ResultDashboardLayoutProblem.properties.data.$ref")
                        .value("#/components/schemas/DashboardLayoutProblemVO"))
                .andExpect(jsonPath("$.components.schemas.ResultDashboardLayoutProblem.properties.timestamp").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard-layout'].put.responses['400'].content").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard-layout'].put.responses['404'].content").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard-layout'].put.responses['409'].content").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard-layout'].put.responses['422'].content").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard-layout'].put.responses['503'].content.*.schema.$ref")
                        .value("#/components/schemas/Result"));
    }

    @Test
    void apiDocsDescribeLegacyPositionsAgainstCurrentDynamicLayout() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/printers/positions'].put.deprecated").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/printers/positions'].put.description")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("当前布局内"),
                                org.hamcrest.Matchers.containsString("30×60"),
                                org.hamcrest.Matchers.containsString("1800"))))
                .andExpect(jsonPath("$.components.schemas.PrinterPositionUpdateDTO.description")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("当前动态布局内"),
                                org.hamcrest.Matchers.containsString("30×60"),
                                org.hamcrest.Matchers.containsString("1800"))))
                .andExpect(jsonPath("$.components.schemas.PrinterPositionUpdateDTO.properties.gridRow.description")
                        .value(org.hamcrest.Matchers.containsString("布局行数上限 30")))
                .andExpect(jsonPath("$.components.schemas.PrinterPositionUpdateDTO.properties.gridCol.description")
                        .value(org.hamcrest.Matchers.containsString("布局列数上限 60")))
                .andExpect(jsonPath("$.components.schemas.PrinterAddDTO.properties.gridRow").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.PrinterAddDTO.properties.gridCol").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.PrinterUpdateDTO.properties.gridRow").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.PrinterUpdateDTO.properties.gridCol").doesNotExist());
    }

    @Test
    void legacyPrinterPositionsDelegateToLayoutServiceAndKeepResponseCompatibility() throws Exception {
        when(dashboardLayoutService.saveLegacyPositions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DashboardLayoutSaveResultVO());

        mockMvc.perform(put("/api/v1/printers/positions")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("[{\"id\":403,\"gridRow\":1,\"gridCol\":2}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value("成功更新 1/1 台设备的位置"));
        org.mockito.Mockito.verify(dashboardLayoutService).saveLegacyPositions(
                org.mockito.ArgumentMatchers.any());
    }

    private String validPayload() {
        return "{\"expectedVersion\":1,\"rowCount\":4,\"columnCount\":12,"
                + "\"aisles\":[],\"overflowPolicy\":\"REJECT\",\"positionChanges\":[]}";
    }
}
