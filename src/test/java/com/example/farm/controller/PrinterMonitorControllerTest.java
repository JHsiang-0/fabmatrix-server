package com.example.farm.controller;

import com.example.farm.FarmApplication;
import com.example.farm.entity.Printer;
import com.example.farm.service.PrinterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FarmApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrinterMonitorControllerTest {

    private static final String SINGLE_MONITOR_URL = "/api/v1/printers/30/monitor";
    private static final String BATCH_MONITOR_URL = "/api/v1/printers/monitor/batch";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PrinterService printerService;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adminCanEnableAndDisableSingleAndBatchMonitoring(boolean enabled) throws Exception {
        when(printerService.batchUpdateMonitorEnabled(List.of(30L, 31L), enabled)).thenReturn(2);

        mockMvc.perform(put(SINGLE_MONITOR_URL)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monitorEnabled\":" + enabled + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put(BATCH_MONITOR_URL)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"printerIds\":[30,31],\"monitorEnabled\":" + enabled + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));

        verify(printerService).updateMonitorEnabled(30L, enabled);
        verify(printerService).batchUpdateMonitorEnabled(List.of(30L, 31L), enabled);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adminCanUseLegacyEnabledAliasOnBothEndpoints(boolean enabled) throws Exception {
        when(printerService.batchUpdateMonitorEnabled(List.of(30L), enabled)).thenReturn(1);

        mockMvc.perform(put(SINGLE_MONITOR_URL)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put(BATCH_MONITOR_URL)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"printerIds\":[30],\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));

        verify(printerService).updateMonitorEnabled(30L, enabled);
        verify(printerService).batchUpdateMonitorEnabled(List.of(30L), enabled);
    }

    @ParameterizedTest
    @MethodSource("validUpdateRequests")
    void operatorCannotChangeMonitoring(String url, String payload) throws Exception {
        mockMvc.perform(put(url)
                        .with(user("operator").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(printerService);
    }

    @ParameterizedTest
    @MethodSource("anonymousRequests")
    void anonymousCannotReadOrChangeMonitoring(String method, String url, String payload) throws Exception {
        MockHttpServletRequestBuilder request = "GET".equals(method) ? get(url) : put(url);
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(payload);
        }

        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(printerService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OPERATOR"})
    void bothRolesCanReadMonitoringHealthWithoutApiKey(String role) throws Exception {
        Printer printer = new Printer();
        printer.setId(30L);
        printer.setName("Farm printer");
        printer.setApiKey("private-monitor-api-key");
        printer.setMonitorEnabled(true);
        printer.setStatus("PRINTING");
        printer.setConnectionStatus("OFFLINE");
        printer.setLastSeenAt(LocalDateTime.of(2026, 10, 8, 10, 30));
        printer.setLastCommunicationError("Connection timed out");
        printer.setConsecutiveFailureCount(3);
        printer.setLastKnownStatus("PRINTING");
        printer.setLastProtocolState("printing");
        printer.setLastDeviceUptime(3600L);
        when(printerService.getById(30L)).thenReturn(printer);

        mockMvc.perform(get(SINGLE_MONITOR_URL).with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("30"))
                .andExpect(jsonPath("$.data.monitorEnabled").value(true))
                .andExpect(jsonPath("$.data.status").value("PRINTING"))
                .andExpect(jsonPath("$.data.effectiveStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.data.connectionStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.data.lastSeenAt").exists())
                .andExpect(jsonPath("$.data.lastCommunicationError").value("Connection timed out"))
                .andExpect(jsonPath("$.data.consecutiveFailureCount").value(3))
                .andExpect(jsonPath("$.data.lastKnownStatus").value("PRINTING"))
                .andExpect(jsonPath("$.data.lastProtocolState").value("printing"))
                .andExpect(jsonPath("$.data.lastDeviceUptime").value(3600))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(content().string(not(containsString("private-monitor-api-key"))));

        verify(printerService).getById(30L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OPERATOR"})
    void unknownPrinterReturns404ForBothRoles(String role) throws Exception {
        when(printerService.getById(404L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/printers/404/monitor")
                        .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verify(printerService).getById(404L);
    }

    @ParameterizedTest
    @MethodSource("invalidUpdateRequests")
    void invalidPayloadIsRejectedBeforeServiceCall(String url, String payload) throws Exception {
        mockMvc.perform(put(url)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(printerService);
    }

    @Test
    void batchAcceptsExactly100Printers() throws Exception {
        List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
        when(printerService.batchUpdateMonitorEnabled(ids, true)).thenReturn(100);

        mockMvc.perform(put(BATCH_MONITOR_URL)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchPayload(100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(100));

        verify(printerService).batchUpdateMonitorEnabled(ids, true);
    }

    private static Stream<Arguments> validUpdateRequests() {
        return Stream.of(
                Arguments.of(SINGLE_MONITOR_URL, "{\"monitorEnabled\":true}"),
                Arguments.of(BATCH_MONITOR_URL, "{\"printerIds\":[30],\"monitorEnabled\":true}"));
    }

    private static Stream<Arguments> anonymousRequests() {
        return Stream.of(
                Arguments.of("GET", SINGLE_MONITOR_URL, null),
                Arguments.of("PUT", SINGLE_MONITOR_URL, "{\"monitorEnabled\":true}"),
                Arguments.of("PUT", BATCH_MONITOR_URL, "{\"printerIds\":[30],\"monitorEnabled\":true}"));
    }

    private static Stream<Arguments> invalidUpdateRequests() {
        return Stream.of(
                Arguments.of(SINGLE_MONITOR_URL, ""),
                Arguments.of(SINGLE_MONITOR_URL, "null"),
                Arguments.of(SINGLE_MONITOR_URL, "{}"),
                Arguments.of(SINGLE_MONITOR_URL, "{\"monitorEnabled\":null}"),
                Arguments.of(BATCH_MONITOR_URL, ""),
                Arguments.of(BATCH_MONITOR_URL, "null"),
                Arguments.of(BATCH_MONITOR_URL, "{}"),
                Arguments.of(BATCH_MONITOR_URL, "{\"printerIds\":[30]}"),
                Arguments.of(BATCH_MONITOR_URL, "{\"printerIds\":[30],\"monitorEnabled\":null}"),
                Arguments.of(BATCH_MONITOR_URL, "{\"monitorEnabled\":true}"),
                Arguments.of(BATCH_MONITOR_URL, "{\"printerIds\":null,\"monitorEnabled\":true}"),
                Arguments.of(BATCH_MONITOR_URL, "{\"printerIds\":[],\"monitorEnabled\":true}"),
                Arguments.of(BATCH_MONITOR_URL, "{\"printerIds\":[30,null],\"monitorEnabled\":true}"),
                Arguments.of(BATCH_MONITOR_URL, batchPayload(101)));
    }

    private static String batchPayload(int size) {
        String ids = LongStream.rangeClosed(1, size)
                .mapToObj(Long::toString)
                .collect(Collectors.joining(","));
        return "{\"printerIds\":[" + ids + "],\"monitorEnabled\":true}";
    }
}
