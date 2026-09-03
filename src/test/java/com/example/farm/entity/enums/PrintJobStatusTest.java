package com.example.farm.entity.enums;

import com.example.farm.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrintJobStatusTest {

    @Test
    void normalizesLegacyStatuses() {
        assertThat(PrintJobStatus.normalize("PENDING")).isEqualTo("QUEUED");
        assertThat(PrintJobStatus.normalize("MANUAL")).isEqualTo("QUEUED");
        assertThat(PrintJobStatus.normalize("CANCELED")).isEqualTo("CANCELLED");
        assertThat(PrintJobStatus.normalize("printing")).isEqualTo("PRINTING");
    }

    @Test
    void acceptsConfiguredStateFlow() {
        PrintJobStatus.requireTransition("QUEUED", PrintJobStatus.ASSIGNED);
        PrintJobStatus.requireTransition("ASSIGNED", PrintJobStatus.UPLOADING);
        PrintJobStatus.requireTransition("UPLOADING", PrintJobStatus.READY);
        PrintJobStatus.requireTransition("ASSIGNED", PrintJobStatus.READY);
        PrintJobStatus.requireTransition("READY", PrintJobStatus.PRINTING);
        PrintJobStatus.requireTransition("PRINTING", PrintJobStatus.PAUSED);
        PrintJobStatus.requireTransition("PAUSED", PrintJobStatus.PRINTING);
        PrintJobStatus.requireTransition("PRINTING", PrintJobStatus.COMPLETED);
        PrintJobStatus.requireTransition("FAILED", PrintJobStatus.QUEUED);
        PrintJobStatus.requireTransition("PRINTING", PrintJobStatus.RECONCILING);
        PrintJobStatus.requireTransition("RECONCILING", PrintJobStatus.COMPLETED);
    }

    @Test
    void rejectsIllegalStateFlowWith422() {
        assertThatThrownBy(() -> PrintJobStatus.requireTransition("QUEUED", PrintJobStatus.PRINTING))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(422L);

        assertThatThrownBy(() -> PrintJobStatus.requireTransition("COMPLETED", PrintJobStatus.QUEUED))
                .isInstanceOf(BusinessException.class);
    }
}
