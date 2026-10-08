package com.example.farm.controller;

import com.example.farm.FarmApplication;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.export.ExportDocument;
import com.example.farm.entity.dto.request.PrintJobHistoryExportRequest;
import com.example.farm.service.PrintJobHistoryExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses the application's actual route security and exception advice. */
@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrintJobExportControllerTest {
    private static final String URL = "/api/v1/print-jobs/history/export";
    @Autowired private MockMvc mvc;
    @MockitoBean private PrintJobHistoryExportService service;

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OPERATOR"})
    void authorizedRolesDownloadBinaryWithCorrectUtf8FilenameAndCacheHeaders(String role) throws Exception {
        byte[] pdf = "%PDF-1.7\nfixture".getBytes(StandardCharsets.UTF_8);
        when(service.export(any())).thenReturn(new ExportDocument("打印历史-20261008.pdf", "application/pdf", pdf));
        var response = mvc.perform(post(URL).with(user("10").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"PDF\"}"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(pdf)).andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdf.length)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse();
        ContentDisposition disposition = ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(disposition.getType()).isEqualTo("attachment");
        assertThat(disposition.getFilename()).isEqualTo("打印历史-20261008.pdf");
        verify(service).export(any());
    }

    @Test
    void excelDownloadRetainsLargeStringFilterIdsWithoutPrecisionLoss() throws Exception {
        byte[] bytes = new byte[] {80, 75, 3, 4};
        when(service.export(any())).thenReturn(new ExportDocument("history.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
        mvc.perform(post(URL).with(user("10").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"XLSX\",\"printerId\":\"9007199254740993\",\"userId\":\"9007199254740995\","
                                + "\"startTime\":\"2026-10-08T10:00:00\",\"endTime\":\"2026-10-08T11:00:00\"}"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        ArgumentCaptor<PrintJobHistoryExportRequest> request = ArgumentCaptor.forClass(PrintJobHistoryExportRequest.class);
        verify(service).export(request.capture());
        assertThat(request.getValue().getPrinterId()).isEqualTo(9007199254740993L);
        assertThat(request.getValue().getUserId()).isEqualTo(9007199254740995L);
        assertThat(request.getValue().getStartTime()).isEqualTo(java.time.LocalDateTime.parse("2026-10-08T10:00:00"));
        assertThat(request.getValue().getEndTime()).isEqualTo(java.time.LocalDateTime.parse("2026-10-08T11:00:00"));
    }

    @Test
    void anonymousIsDeniedBeforeExport() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"PDF\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedRoleIsDeniedBeforeExport() throws Exception {
        mvc.perform(post(URL).with(user("10").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"PDF\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"format\":\"CSV\"}", "{\"format\":\"PDF\",\"statuses\":[]}",
            "{\"format\":\"PDF\",\"printerId\":\"0\"}",
            "{\"format\":\"PDF\",\"startTime\":\"2026-13-30T12:00:00\"}",
            "{\"format\":\"PDF\",\"startTime\":\"2026-02-30T12:00:00\"}",
            "{\"format\":\"PDF\",\"startTime\":\"2026-10-08T12:00:00Z\"}",
            "{\"format\":\"PDF\",\"statuses\":[\"COMPLETED\",\"FAILED\",\"CANCELLED\",\"CANCELED\"]}"})
    void invalidRequestIsRejectedAsJsonBeforeGeneration(String body) throws Exception {
        mvc.perform(post(URL).with(user("10").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void excessiveRecordsReturnStructured422InsteadOfDownloadedErrorFile() throws Exception {
        when(service.export(any())).thenThrow(new BusinessException(422, "导出记录超过1000条，请缩小筛选范围"));
        mvc.perform(post(URL).with(user("10").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"PDF\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("导出记录超过1000条，请缩小筛选范围"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void allowedBrowserOriginCanReadDownloadedFilename() throws Exception {
        when(service.export(any())).thenReturn(new ExportDocument("history.pdf", "application/pdf", new byte[] {37, 80, 68, 70}));
        mvc.perform(post(URL).with(user("10").roles("ADMIN"))
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"PDF\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.containsString("Content-Disposition")));
    }
}
