package com.example.farm.task;

import com.example.farm.service.PrinterEventOutbox;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrinterEventOutboxHealthTaskTest {

    @Test
    void warnsAtEightyPercentAndRecoversBelowSixtyPercentWithoutFlapping() {
        PrinterEventOutbox outbox = mock(PrinterEventOutbox.class);
        PrinterEventOutboxHealthTask task = new PrinterEventOutboxHealthTask(outbox, 100);

        when(outbox.pendingCount()).thenReturn(79, 80, 70, 60, 59);
        task.checkBacklog();
        assertThat(task.isWarningActive()).isFalse();

        task.checkBacklog();
        assertThat(task.isWarningActive()).isTrue();

        task.checkBacklog();
        task.checkBacklog();
        assertThat(task.isWarningActive()).isTrue();

        task.checkBacklog();
        assertThat(task.isWarningActive()).isFalse();
    }

    @Test
    void rejectsCapacityOutsideOutboxLimits() {
        PrinterEventOutbox outbox = mock(PrinterEventOutbox.class);

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrinterEventOutboxHealthTask(outbox, 0));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrinterEventOutboxHealthTask(outbox, 100_001));
    }
}
