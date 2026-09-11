package com.example.farm.controller;

import com.example.farm.FarmApplication;
import com.example.farm.entity.vo.DashboardLayoutLimitsVO;
import com.example.farm.entity.vo.DashboardLayoutVO;
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
}
