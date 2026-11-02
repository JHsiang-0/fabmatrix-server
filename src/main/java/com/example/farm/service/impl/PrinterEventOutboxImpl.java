package com.example.farm.service.impl;

import com.example.farm.protocol.model.CanonicalPrinterEvent;
import com.example.farm.service.PrinterEventOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** SQLite persistence for retryable device events; acknowledged rows are deleted, pending rows never age out. */
@Service
@Profile("local")
public class PrinterEventOutboxImpl implements PrinterEventOutbox {
    private static final int DEFAULT_MAX_PENDING_EVENTS = 10_000;
    private static final int MAX_BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final int maxPendingEvents;

    public PrinterEventOutboxImpl(JdbcTemplate jdbcTemplate,
                                  @Value("${farm.event-outbox.max-pending-events:10000}") int maxPendingEvents) {
        if (maxPendingEvents < 1 || maxPendingEvents > 100_000) {
            throw new IllegalArgumentException("farm.event-outbox.max-pending-events must be between 1 and 100000");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.maxPendingEvents = maxPendingEvents;
    }

    @Override
    @Transactional
    public boolean enqueue(CanonicalPrinterEvent event) {
        if (event == null) throw new IllegalArgumentException("Event is required");
        CanonicalPrinterEvent existing = findById(event.eventId());
        if (existing != null) return assertSameEvent(existing, event);

        int inserted = jdbcTemplate.update("""
                INSERT INTO farm_printer_event_outbox
                    (event_id, source_instance_id, device_uid, local_printer_id, execution_id,
                     event_type, occurred_at, severity, code, safe_message, correlation_id)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE (SELECT COUNT(*) FROM farm_printer_event_outbox) < ?
                ON CONFLICT(event_id) DO NOTHING
                """, event.eventId().toString(), event.sourceInstanceId().toString(), event.deviceUid().toString(),
                event.localPrinterId(), event.executionId() == null ? null : event.executionId().toString(),
                event.type().name(), Timestamp.from(event.occurredAt()), event.severity().name(), event.code(),
                event.safeMessage(), event.correlationId() == null ? null : event.correlationId().toString(),
                maxPendingEvents);
        if (inserted == 1) return true;

        existing = findById(event.eventId());
        if (existing != null) return assertSameEvent(existing, event);
        throw new IllegalStateException("Printer event outbox is full; event was not accepted");
    }

    @Override
    @Transactional(readOnly = true)
    public List<CanonicalPrinterEvent> findPending(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Event outbox batch size must be between 1 and 100");
        }
        return jdbcTemplate.query("""
                SELECT event_id, source_instance_id, device_uid, local_printer_id, execution_id,
                       event_type, occurred_at, severity, code, safe_message, correlation_id
                FROM farm_printer_event_outbox ORDER BY id ASC LIMIT ?
                """, (rs, row) -> new CanonicalPrinterEvent(
                UUID.fromString(rs.getString("event_id")),
                UUID.fromString(rs.getString("source_instance_id")),
                UUID.fromString(rs.getString("device_uid")), rs.getString("local_printer_id"),
                uuidOrNull(rs.getString("execution_id")),
                CanonicalPrinterEvent.Type.valueOf(rs.getString("event_type")),
                rs.getTimestamp("occurred_at").toInstant(),
                CanonicalPrinterEvent.Severity.valueOf(rs.getString("severity")),
                rs.getString("code"), rs.getString("safe_message"), uuidOrNull(rs.getString("correlation_id"))), limit);
    }

    @Override
    @Transactional
    public void recordAttempt(UUID eventId) {
        if (eventId == null) throw new IllegalArgumentException("Event ID is required");
        jdbcTemplate.update("UPDATE farm_printer_event_outbox SET attempt_count = attempt_count + 1, "
                + "last_attempt_at = CURRENT_TIMESTAMP WHERE event_id = ?", eventId.toString());
    }

    @Override
    @Transactional
    public void acknowledge(UUID eventId) {
        if (eventId == null) throw new IllegalArgumentException("Event ID is required");
        jdbcTemplate.update("DELETE FROM farm_printer_event_outbox WHERE event_id = ?", eventId.toString());
    }

    @Override
    @Transactional(readOnly = true)
    public int pendingCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM farm_printer_event_outbox", Integer.class);
        return count == null ? 0 : count;
    }

    private CanonicalPrinterEvent findById(UUID eventId) {
        List<CanonicalPrinterEvent> rows = jdbcTemplate.query("""
                SELECT event_id, source_instance_id, device_uid, local_printer_id, execution_id,
                       event_type, occurred_at, severity, code, safe_message, correlation_id
                FROM farm_printer_event_outbox WHERE event_id = ?
                """, (rs, row) -> new CanonicalPrinterEvent(
                UUID.fromString(rs.getString("event_id")),
                UUID.fromString(rs.getString("source_instance_id")),
                UUID.fromString(rs.getString("device_uid")), rs.getString("local_printer_id"),
                uuidOrNull(rs.getString("execution_id")),
                CanonicalPrinterEvent.Type.valueOf(rs.getString("event_type")),
                rs.getTimestamp("occurred_at").toInstant(),
                CanonicalPrinterEvent.Severity.valueOf(rs.getString("severity")),
                rs.getString("code"), rs.getString("safe_message"), uuidOrNull(rs.getString("correlation_id"))),
                eventId.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean assertSameEvent(CanonicalPrinterEvent existing, CanonicalPrinterEvent incoming) {
        if (!existing.equals(incoming)) {
            throw new IllegalStateException("Printer event ID was reused with a different payload");
        }
        return false;
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
