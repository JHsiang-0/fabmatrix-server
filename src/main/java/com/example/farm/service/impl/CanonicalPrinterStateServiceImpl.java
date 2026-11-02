package com.example.farm.service.impl;

import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.model.CanonicalPrinterModelMapper;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.service.CanonicalPrinterStateService;
import com.example.farm.service.PrinterExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** SQLite-only latest canonical state store; network/reporting is deliberately outside this class. */
@Service
@Profile("local")
@RequiredArgsConstructor
public class CanonicalPrinterStateServiceImpl implements CanonicalPrinterStateService {
    private final JdbcTemplate jdbcTemplate;
    private final PrinterMapper printerMapper;
    private final PrinterExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final UUID deviceBootId = UUID.randomUUID();

    @Override
    public boolean persist(Printer printer, PrinterObservation observation) {
        if (printer == null || printer.getId() == null || observation == null
                || observation.completeness() == null
                || observation.completeness() == PrinterObservation.Completeness.INCONSISTENT) {
            return false;
        }
        Printer current = printerMapper.selectById(printer.getId());
        if (current == null || current.getDeviceUid() == null || current.getDeviceUid().isBlank()) {
            return false;
        }
        Map<String, Object> previous = previous(current.getId());
        long observationRevision = previous == null ? 1
                : ((Number) previous.get("observation_revision")).longValue() + 1;
        long descriptorRevision = previous == null ? 1
                : ((Number) previous.get("descriptor_revision")).longValue();
        boolean continuityUnknown = previous == null
                || !deviceBootId.toString().equals(previous.get("device_boot_id"));
        UUID sourceInstanceId = UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT source_instance_id FROM farm_installation_identity WHERE id = 1", String.class));
        var activeExecution = executionService.findActive(current.getId());
        CanonicalPrinterModel model = CanonicalPrinterModelMapper.map(current, sourceInstanceId,
                deviceBootId, observationRevision, descriptorRevision, observation, activeExecution,
                continuityUnknown, Instant.now());
        String descriptorJson = serialize(model.descriptor());
        if (previous != null && !descriptorJson.equals(previous.get("descriptor_json"))) {
            descriptorRevision++;
            model = CanonicalPrinterModelMapper.map(current, sourceInstanceId, deviceBootId,
                    observationRevision, descriptorRevision, observation, activeExecution,
                    continuityUnknown, Instant.now());
            descriptorJson = serialize(model.descriptor());
        }
        String modelJson = serialize(model);
        jdbcTemplate.update("""
                INSERT INTO farm_printer_canonical_state
                    (printer_id, device_boot_id, observation_revision, descriptor_revision,
                     descriptor_json, model_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(printer_id) DO UPDATE SET
                    device_boot_id=excluded.device_boot_id,
                    observation_revision=excluded.observation_revision,
                    descriptor_revision=excluded.descriptor_revision,
                    descriptor_json=excluded.descriptor_json,
                    model_json=excluded.model_json,
                    updated_at=CURRENT_TIMESTAMP
                """, current.getId(), deviceBootId.toString(), observationRevision,
                descriptorRevision, descriptorJson, modelJson);
        return true;
    }

    private Map<String, Object> previous(Long printerId) {
        try {
            return jdbcTemplate.queryForMap("SELECT device_boot_id, observation_revision, descriptor_revision, "
                    + "descriptor_json FROM farm_printer_canonical_state WHERE printer_id = ?", printerId);
        } catch (EmptyResultDataAccessException missing) {
            return null;
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize canonical printer state", failure);
        }
    }
}
