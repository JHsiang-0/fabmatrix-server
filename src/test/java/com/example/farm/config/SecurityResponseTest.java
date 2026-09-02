package com.example.farm.config;

import com.example.farm.FarmApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestUsesUnified401Response() throws Exception {
        mockMvc.perform(get("/api/v1/printers/page"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void operatorCannotAccessAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/auth/admin/users")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void rejectsInvalidPrinterPaginationParameters() throws Exception {
        mockMvc.perform(get("/api/v1/printers/page")
                        .param("pageNum", "0")
                        .param("pageSize", "101")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void operatorCannotScanPrinters() throws Exception {
        mockMvc.perform(get("/api/v1/printers/scan")
                        .param("subnet", "192.168.1")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void unauthenticatedCannotResumePrinter() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/control/403/resume"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void standardCreateJobEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/print-jobs")
                        .contentType("application/json")
                        .content("{\"fileId\":20,\"priority\":0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void validatesPrinterHistoryPaginationBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/v1/printers/403/history")
                        .param("pageNum", "0")
                        .param("pageSize", "101")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
