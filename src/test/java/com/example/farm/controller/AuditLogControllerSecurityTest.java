package com.example.farm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.FarmApplication;
import com.example.farm.entity.vo.AuditLogVO;
import com.example.farm.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    void adminCanFilterAndPageAuditLogs() throws Exception {
        AuditLogVO record = new AuditLogVO();
        record.setId(9L);
        record.setActorId(1L);
        record.setActorUsername("admin");
        record.setActorRole("ADMIN");
        record.setAction("JOB_CANCEL");
        record.setTargetType("JOB");
        record.setTargetId("9");
        record.setTargetLabel("模型任务");
        record.setResult("SUCCESS");
        Page<AuditLogVO> page = new Page<>(2, 20, 21);
        page.setRecords(List.of(record));
        when(auditLogService.pageAuditLogs(any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/auth/admin/audit-logs")
                        .param("pageNum", "2")
                        .param("pageSize", "20")
                        .param("action", "JOB_CANCEL")
                        .param("result", "SUCCESS")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.records[0].action").value("JOB_CANCEL"))
                .andExpect(jsonPath("$.data.records[0].targetLabel").value("模型任务"))
                .andExpect(jsonPath("$.data.records[0].password").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].token").doesNotExist());
    }

    @Test
    void operatorCannotReadAuditLogs() throws Exception {
        mockMvc.perform(get("/api/v1/auth/admin/audit-logs")
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
