package com.example.farm.config;

import com.example.farm.common.api.Result;
import com.example.farm.controller.FarmStatusMessage;
import com.example.farm.entity.dto.DashboardLayoutSaveRequest;
import com.example.farm.entity.vo.PrinterVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommonObjectMapperConfigTest {

    private static final long LARGE_ID = 9_007_199_254_740_993L;

    private final ObjectMapper objectMapper = new CommonObjectMapperConfig().objectMapper();

    @Test
    void serializesLongIdsAsStringsInRestAndWebSocketPayloads() throws Exception {
        PrinterVO printer = new PrinterVO();
        printer.setId(LARGE_ID);
        String rest = objectMapper.writeValueAsString(Result.success(printer));
        String websocket = objectMapper.writeValueAsString(
                FarmStatusMessage.printerStatus(LARGE_ID, Map.of("jobId", LARGE_ID)));

        assertThat(rest).contains("\"id\":\"9007199254740993\"");
        assertThat(websocket)
                .contains("\"printerId\":\"9007199254740993\"")
                .contains("\"jobId\":\"9007199254740993\"");
    }

    @Test
    void keepsStringLongInputDeserializationCompatible() throws Exception {
        DashboardLayoutSaveRequest request = objectMapper.readValue(
                "{\"expectedVersion\":\"9007199254740993\"}", DashboardLayoutSaveRequest.class);

        assertThat(request.getExpectedVersion()).isEqualTo(LARGE_ID);
    }
}
