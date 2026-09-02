package com.example.farm.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterDeviceStatusTest {

    @Test
    void representsProtocolIndependentStatusWithoutCredentials() throws Exception {
        PrinterDeviceStatus status = new PrinterDeviceStatus(
                PrinterStatus.PRINTING,
                "printing",
                null,
                "demo.gcode",
                new BigDecimal("35.5"),
                new BigDecimal("210"),
                new BigDecimal("215"),
                new BigDecimal("60"),
                new BigDecimal("60"),
                new BigDecimal("120"),
                new BigDecimal("340"),
                new BigDecimal("1500")
        );

        String json = new ObjectMapper().writeValueAsString(status);

        assertThat(status.status()).isEqualTo(PrinterStatus.PRINTING);
        assertThat(json).contains("PRINTING", "demo.gcode").doesNotContain("apiKey", "password", "rustfsKey");
    }
}
