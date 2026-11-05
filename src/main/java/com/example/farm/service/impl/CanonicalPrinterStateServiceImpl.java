package com.example.farm.service.impl;

import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.model.CanonicalPrinterModelMapper;
import com.example.farm.protocol.model.CanonicalPrinterModelValidator;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.service.CanonicalPrinterStateService;
import com.example.farm.service.PrinterExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.HashMap;
import java.util.Collections;
import org.springframework.jdbc.core.RowCallbackHandler;

/** SQLite-only latest canonical state store; network/reporting is deliberately outside this class. */
@Service
@Profile("local")
@RequiredArgsConstructor
@Slf4j
public class CanonicalPrinterStateServiceImpl implements CanonicalPrinterStateService {
    private static final Duration CONNECTION_FRESHNESS = Duration.ofSeconds(30);
    private static final int CLOUD_MODEL_MAX_BYTES = 128 * 1024;

    private final JdbcTemplate jdbcTemplate;
    private final PrinterMapper printerMapper;
    private final PrinterExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final UUID deviceBootId = UUID.randomUUID();
    private final Map<Long, Long> oversizedWarnings = new java.util.concurrent.ConcurrentHashMap<>();

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
        var lastFinishedExecution = executionService.findLastFinished(current.getId());
        CanonicalPrinterModel.Descriptor previousDescriptor = previous == null ? null
                : readDescriptor((String) previous.get("descriptor_json"));
        CanonicalPrinterModel model = CanonicalPrinterModelMapper.map(current, sourceInstanceId,
                deviceBootId, observationRevision, descriptorRevision, observation, activeExecution,
                lastFinishedExecution, continuityUnknown, Instant.now());
        model = preserveCapabilityTimes(model, previousDescriptor);
        String descriptorJson = serialize(model.descriptor());
        if (previous != null && !descriptorJson.equals(previous.get("descriptor_json"))) {
            descriptorRevision++;
            model = CanonicalPrinterModelMapper.map(current, sourceInstanceId, deviceBootId,
                    observationRevision, descriptorRevision, observation, activeExecution,
                    lastFinishedExecution, continuityUnknown, Instant.now());
            model = preserveCapabilityTimes(model, previousDescriptor);
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

    @Override
    public CanonicalPrinterModel findLatest(long printerId) {
        return findLatest(printerId, false);
    }

    @Override
    public CanonicalPrinterModel findLatestForCloud(long printerId) {
        return findLatest(printerId, true);
    }

    @Override
    public Map<Long, CanonicalPrinterModel> findLatestBatch(List<Printer> printers) {
        Map<Long, Printer> byId = new HashMap<>();
        for (Printer printer : printers) {
            if (printer.getId() != null) { byId.put(printer.getId(), printer); }
        }
        var ids = List.copyOf(byId.keySet());
        Map<Long, CanonicalPrinterModel> models = new HashMap<>();
        for (int offset = 0; offset < ids.size(); offset += 200) {
            var batch = ids.subList(offset, Math.min(ids.size(), offset + 200));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            jdbcTemplate.query("SELECT printer_id,model_json FROM farm_printer_canonical_state WHERE printer_id IN ("
                    + placeholders + ")", statement -> {
                for (int index = 0; index < batch.size(); index++) { statement.setLong(index + 1, batch.get(index)); }
            }, (RowCallbackHandler) row -> {
                long id = row.getLong("printer_id");
                try {
                    var model = objectMapper.readValue(row.getString("model_json"), CanonicalPrinterModel.class);
                    CanonicalPrinterModelValidator.validate(model);
                    Printer printer = byId.get(id);
                    if (!String.valueOf(id).equals(model.identity().localPrinterId())
                            || printer.getDeviceUid() != null && !printer.getDeviceUid().equals(model.identity().deviceUid().toString())) {
                        log.warn("设备列表模型身份不一致，已隐藏状态: printerId={}", id);
                        return;
                    }
                    models.put(id, model.withConnectionHealth(currentConnection(printer)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
                    log.warn("设备列表模型无效，已隐藏状态: printerId={}", id);
                }
            });
        }
        return models;
    }

    private CanonicalPrinterModel findLatest(long printerId, boolean bounded) {
        if (printerId <= 0) return null;
        String modelJson;
        try {
            modelJson = bounded
                    ? jdbcTemplate.queryForObject("SELECT CASE WHEN length(CAST(model_json AS BLOB)) <= ? "
                            + "THEN model_json ELSE NULL END FROM farm_printer_canonical_state WHERE printer_id = ?",
                            String.class, CLOUD_MODEL_MAX_BYTES, printerId)
                    : jdbcTemplate.queryForObject(
                            "SELECT model_json FROM farm_printer_canonical_state WHERE printer_id = ?",
                            String.class, printerId);
        } catch (EmptyResultDataAccessException missing) {
            return null;
        }
        if (modelJson == null) {
            long now = System.currentTimeMillis();
            if (oversizedWarnings.size() >= 200 && !oversizedWarnings.containsKey(printerId)) {
                oversizedWarnings.clear();
            }
            oversizedWarnings.compute(printerId, (id, last) -> {
                if (last == null || now - last >= Duration.ofMinutes(5).toMillis()) {
                    log.warn("Cloud canonical model exceeds {} bytes; skipping printerId={}", CLOUD_MODEL_MAX_BYTES, id);
                    return now;
                }
                return last;
            });
            return null;
        }
        try {
            CanonicalPrinterModel model = objectMapper.readValue(modelJson, CanonicalPrinterModel.class);
            CanonicalPrinterModelValidator.validate(model);
            Printer printer = printerMapper.selectById(printerId);
            return printer == null ? null : model.withConnectionHealth(currentConnection(printer));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
            throw new InvalidStoredModelException(invalid);
        }
    }

    private CanonicalPrinterModel.Connection currentConnection(Printer printer) {
        Instant lastSuccessfulAt = printer.getLastSeenAt() == null ? null
                : printer.getLastSeenAt().atZone(ZoneId.systemDefault()).toInstant();
        String status = printer.getConnectionStatus() == null ? "UNKNOWN"
                : printer.getConnectionStatus().trim().toUpperCase(java.util.Locale.ROOT);
        CanonicalPrinterModel.ConnectionStatus currentStatus;
        if (!Boolean.TRUE.equals(printer.getMonitorEnabled())) {
            currentStatus = CanonicalPrinterModel.ConnectionStatus.UNKNOWN;
        } else if ("OFFLINE".equals(status)) {
            currentStatus = CanonicalPrinterModel.ConnectionStatus.OFFLINE;
        } else if ("ONLINE".equals(status) && lastSuccessfulAt != null
                && lastSuccessfulAt.isAfter(Instant.now().minus(CONNECTION_FRESHNESS))) {
            currentStatus = CanonicalPrinterModel.ConnectionStatus.ONLINE;
        } else {
            currentStatus = CanonicalPrinterModel.ConnectionStatus.UNKNOWN;
        }
        return new CanonicalPrinterModel.Connection(currentStatus, Boolean.TRUE.equals(printer.getMonitorEnabled()),
                lastSuccessfulAt, printer.getConsecutiveFailureCount() == null
                ? 0 : Math.max(0, printer.getConsecutiveFailureCount()));
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

    private CanonicalPrinterModel.Descriptor readDescriptor(String json) {
        try {
            return objectMapper.readValue(json, CanonicalPrinterModel.Descriptor.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private CanonicalPrinterModel preserveCapabilityTimes(CanonicalPrinterModel model,
                                                            CanonicalPrinterModel.Descriptor previous) {
        if (previous == null) return model;
        var descriptor = model.descriptor();
        var capabilities = descriptor.capabilities().stream().map(capability -> {
            var existing = previous.capabilities().stream().filter(old -> old.code().equals(capability.code())
                    && old.support() == capability.support() && old.evidence() == capability.evidence())
                    .findFirst();
            return existing.orElse(capability);
        }).toList();
        var stable = new CanonicalPrinterModel.Descriptor(descriptor.revision(), descriptor.name(),
                descriptor.machineNumber(), descriptor.firmwareFamily(), descriptor.firmwareVersion(),
                descriptor.connectionMode(), descriptor.machineMode(), descriptor.emulated(),
                descriptor.primaryToolId(), descriptor.primaryBedHeaterId(), descriptor.tools(),
                descriptor.heaters(), descriptor.fans(), descriptor.axes(), descriptor.sensors(), capabilities,
                descriptor.locationLabel(), descriptor.configuredMaterial(), descriptor.configuredNozzleDiameterMm());
        return new CanonicalPrinterModel(model.modelVersion(), model.identity(), stable, model.observation());
    }
}
