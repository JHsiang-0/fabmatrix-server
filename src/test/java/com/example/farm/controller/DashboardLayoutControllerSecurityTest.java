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

    private String validPayload() {
        return "{\"expectedVersion\":1,\"rowCount\":4,\"columnCount\":12,"
                + "\"aisles\":[],\"overflowPolicy\":\"REJECT\",\"positionChanges\":[]}";
    }
}
