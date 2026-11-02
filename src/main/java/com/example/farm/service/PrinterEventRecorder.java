package com.example.farm.service;

import com.example.farm.entity.Printer;
import com.example.farm.protocol.model.CanonicalPrinterEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** Creates Cloud-contract events from local printer state transitions without exporting diagnostics. */
@Service
@Profile("local")
@RequiredArgsConstructor
public class PrinterEventRecorder {
    private final JdbcTemplate jdbcTemplate;
    private final PrinterEventOutbox outbox;
    private final PrinterExecutionService executionService;

    public boolean record(Printer printer, CanonicalPrinterEvent.Type type,
                          CanonicalPrinterEvent.Severity severity, String code) {
        if (printer == null || printer.getId() == null || printer.getDeviceUid() == null
                || printer.getDeviceUid().isBlank()) {
            return false;
        }
        String source = jdbcTemplate.queryForObject(
                "SELECT source_instance_id FROM farm_installation_identity WHERE id = 1", String.class);
        if (source == null) return false;
        UUID executionId = null;
        if (executionService != null) {
            var active = executionService.findActive(printer.getId());
            if (active != null && active.getExecutionUid() != null) {
                executionId = UUID.fromString(active.getExecutionUid());
            }
        }
        var event = new CanonicalPrinterEvent(UUID.randomUUID(), UUID.fromString(source),
                UUID.fromString(printer.getDeviceUid()), printer.getId().toString(), executionId, type,
                Instant.now(), severity, code, null, null);
        return outbox.enqueue(event);
    }
}
