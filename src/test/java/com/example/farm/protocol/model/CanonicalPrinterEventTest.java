package com.example.farm.protocol.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalPrinterEventTest {
    private static final UUID EVENT_ID = UUID.fromString("b7f98581-c385-4d45-8f1a-7ce0e60dfda1");
    private static final UUID SOURCE_ID = UUID.fromString("107aaf03-bbc3-43ea-a4b9-eef0dc876660");
    private static final UUID DEVICE_ID = UUID.fromString("772fb4da-37dd-4dab-b6a9-afca1b035946");

    @Test
    void acceptsCloudEventVocabularyAndSafeSummary() {
        var event = new CanonicalPrinterEvent(EVENT_ID, SOURCE_ID, DEVICE_ID, "42", null,
                CanonicalPrinterEvent.Type.DEVICE_DISCONNECTED, Instant.parse("2026-11-02T04:00:00Z"),
                CanonicalPrinterEvent.Severity.WARNING, "DEVICE_TIMEOUT", "设备连接超时", null);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.safeMessage()).isEqualTo("设备连接超时");
    }

    @Test
    void rejectsUnboundedOrUnapprovedEventFields() {
        assertThatThrownBy(() -> new CanonicalPrinterEvent(EVENT_ID, SOURCE_ID, DEVICE_ID, " ", null,
                CanonicalPrinterEvent.Type.DEVICE_DISCONNECTED, Instant.now(),
                CanonicalPrinterEvent.Severity.WARNING, "DEVICE_TIMEOUT", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalPrinterEvent(EVENT_ID, SOURCE_ID, DEVICE_ID, "42", null,
                CanonicalPrinterEvent.Type.DEVICE_DISCONNECTED, Instant.now(),
                CanonicalPrinterEvent.Severity.WARNING, "device timeout", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalPrinterEvent(EVENT_ID, SOURCE_ID, DEVICE_ID, "42", null,
                CanonicalPrinterEvent.Type.DEVICE_DISCONNECTED, Instant.now(),
                CanonicalPrinterEvent.Severity.WARNING, "DEVICE_TIMEOUT", "secret\nvalue", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
