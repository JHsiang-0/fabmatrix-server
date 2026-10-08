package com.example.farm.controller;

import com.example.farm.FarmApplication;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.vo.PrinterIpSyncItemVO;
import com.example.farm.entity.vo.PrinterRediscoveryResultVO;
import com.example.farm.service.PrinterRediscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrinterRediscoveryControllerTest {

    private static final String URL = "/api/v1/printers/rediscover";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PrinterRediscoveryService service;

    @Test
    void administratorCanStartAndReadSyncWithStringIdsAndNoDeviceCredentials() throws Exception {
        PrinterRediscoveryResultVO result = completedResult();
        when(service.startManual()).thenReturn(result);
        when(service.getLatest()).thenReturn(result);

        for (var request : List.of(post(URL), get(URL))) {
            mvc.perform(request.with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.scanId").value("fixture-scan"))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.updatedCount").value(1))
                    .andExpect(jsonPath("$.data.items[0].printerId").isString())
                    .andExpect(jsonPath("$.data.items[0].printerId").value("9007199254740993"))
                    .andExpect(jsonPath("$.data.items[0].newIpAddress").value("192.168.10.21"))
                    .andExpect(jsonPath("$.data.items[0].apiKey").doesNotExist())
                    .andExpect(content().string(not(containsString("apiKey"))))
                    .andExpect(content().string(not(containsString("rustfsKey"))));
        }

        verify(service).startManual();
        verify(service).getLatest();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void operatorCannotStartOrReadAdminDeviceSync(String method) throws Exception {
        mvc.perform(("GET".equals(method) ? get(URL) : post(URL))
                        .with(user("operator").roles("OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void anonymousCannotStartOrReadDeviceSync(String method) throws Exception {
        mvc.perform("GET".equals(method) ? get(URL) : post(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service);
    }

    @Test
    void unavailableWorkerReturnsAServiceFailureWithoutSuccessPayload() throws Exception {
        when(service.startManual()).thenThrow(new BusinessException(503, "服务正在关闭，请稍后重试"));

        mvc.perform(post(URL).with(user("admin").roles("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));

        verify(service).startManual();
    }

    private static PrinterRediscoveryResultVO completedResult() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 8, 9, 0);
        return new PrinterRediscoveryResultVO("fixture-scan", "COMPLETED", "设备 IP 同步完成", time, time,
                List.of("192.168.10"), 254, 1, 0, 0,
                List.of(new PrinterIpSyncItemVO(9007199254740993L, "Fixture printer", "192.168.10.1",
                        "192.168.10.21", "UPDATED", "等待监控确认")));
    }
}
