package com.example.farm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FarmStatusMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsStablePrinterStatusMessage() throws Exception {
        FarmStatusMessage message = FarmStatusMessage.printerStatus(403L,
                Map.of("status", "PRINTING", "progress", 35.5));

        assertThat(message.version()).isEqualTo("1");
        assertThat(message.type()).isEqualTo("PRINTER_STATUS");
        assertThat(message.printerId()).isEqualTo(403L);
        assertThat(message.timestamp()).isPositive();
        var json = objectMapper.readTree(objectMapper.writeValueAsString(message));
        assertThat(json.get("type").asText()).isEqualTo("PRINTER_STATUS");
        assertThat(json.get("printerId").asLong()).isEqualTo(403L);
        assertThat(json.get("timestamp").asLong()).isPositive();
    }

    @Test
    void rejectsUnknownTypeAndInvalidIdentifiers() {
        assertThatThrownBy(() -> new FarmStatusMessage("UNKNOWN", 403L,
                System.currentTimeMillis(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FarmStatusMessage("PRINTER_STATUS", null,
                System.currentTimeMillis(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FarmStatusMessage("SNAPSHOT", 403L,
                System.currentTimeMillis(), Map.of("printers", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSensitiveFields() {
        assertThatThrownBy(() -> FarmStatusMessage.printerStatus(403L,
                Map.of("status", "IDLE", "apiKey", "secret")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
