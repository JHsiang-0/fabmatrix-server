package com.example.farm.task;

import com.example.farm.service.PrinterEventOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reports a growing local event backlog without interrupting printer operations. */
@Slf4j
@Component
@Profile("local")
public class PrinterEventOutboxHealthTask {
    private static final double WARNING_RATIO = 0.8;
    private static final double RECOVERY_RATIO = 0.6;

    private final PrinterEventOutbox outbox;
    private final int maxPendingEvents;
    private boolean warningActive;

    public PrinterEventOutboxHealthTask(PrinterEventOutbox outbox,
                                        @Value("${farm.event-outbox.max-pending-events:10000}") int maxPendingEvents) {
        if (maxPendingEvents < 1 || maxPendingEvents > 100_000) {
            throw new IllegalArgumentException("farm.event-outbox.max-pending-events must be between 1 and 100000");
        }
        this.outbox = outbox;
        this.maxPendingEvents = maxPendingEvents;
    }

    @Scheduled(fixedDelayString = "${farm.event-outbox.health-check-interval:60s}")
    public void checkBacklog() {
        int pending = outbox.pendingCount();
        int warningThreshold = (int) Math.ceil(maxPendingEvents * WARNING_RATIO);
        int recoveryThreshold = (int) Math.ceil(maxPendingEvents * RECOVERY_RATIO);

        if (!warningActive && pending >= warningThreshold) {
            warningActive = true;
            log.warn("Printer event outbox backlog is high: pending={}, capacity={}, warningThreshold={}",
                    pending, maxPendingEvents, warningThreshold);
        } else if (warningActive && pending < recoveryThreshold) {
            warningActive = false;
            log.info("Printer event outbox backlog recovered: pending={}, capacity={}",
                    pending, maxPendingEvents);
        }
    }

    boolean isWarningActive() {
        return warningActive;
    }
}
