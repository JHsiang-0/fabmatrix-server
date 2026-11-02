package com.example.farm.service;

import com.example.farm.protocol.model.CanonicalPrinterEvent;

import java.util.List;
import java.util.UUID;

/** Durable ordered delivery queue for immutable printer events. */
public interface PrinterEventOutbox {
    /** Returns true for a new event and false for an identical event already queued. */
    boolean enqueue(CanonicalPrinterEvent event);

    List<CanonicalPrinterEvent> findPending(int limit);

    void recordAttempt(UUID eventId);

    /** Removes an event only after the receiver confirms transactionally accepted or duplicate. */
    void acknowledge(UUID eventId);

    int pendingCount();
}
