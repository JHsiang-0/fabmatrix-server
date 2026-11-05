package com.example.farm.cloud;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.CanonicalPrinterStateService;
import com.example.farm.protocol.observation.PrinterObservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@Slf4j
public class CloudSnapshotSource {
    private final PrinterService printers;
    private final PrinterCacheService cache;
    private final ObjectProvider<CanonicalPrinterStateService> canonicalStateServices;
    private final JdbcTemplate jdbcTemplate;
    private final java.util.Map<Long, Long> invalidModelWarnings = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public CloudSnapshotSource(PrinterService printers, PrinterCacheService cache,
                               ObjectProvider<CanonicalPrinterStateService> canonicalStateServices,
                               JdbcTemplate jdbcTemplate) {
        this.printers = printers;
        this.cache = cache;
        this.canonicalStateServices = canonicalStateServices;
        this.jdbcTemplate = jdbcTemplate;
    }

    CloudSnapshotSource(PrinterService printers, PrinterCacheService cache) {
        this.printers = printers;
        this.cache = cache;
        this.canonicalStateServices = null;
        this.jdbcTemplate = null;
    }

    CloudSnapshotSource(PrinterService printers, PrinterCacheService cache,
                       ObjectProvider<CanonicalPrinterStateService> canonicalStateServices) {
        this.printers = printers;
        this.cache = cache;
        this.canonicalStateServices = canonicalStateServices;
        this.jdbcTemplate = null;
    }

    public record CanonicalAsset(String localPrinterId, String deviceUid, String name, String machineNumber,
                                 String firmwareType, CanonicalPrinterModel model) { }
    public long printerCount() { return printers.count(); }
    public record InventoryIdentity(String sourceInstanceId, String deviceUid, String localPrinterId) { }
    public record InventoryDescriptorSummary(String name, String machineNumber, String firmwareFamily) { }
    public record InventoryDevice(InventoryIdentity identity, InventoryDescriptorSummary descriptorSummary) { }

    /** Captures the privacy-filtered asset identity and the last locally persisted full model. */
    public List<CanonicalAsset> captureCanonical() {
        var assets = assets();
        CanonicalPrinterStateService canonicalStates = canonicalStateServices == null
                ? null : canonicalStateServices.getIfAvailable();
        return assets.stream().map(asset -> new CanonicalAsset(asset.getId() == null ? null : asset.getId().toString(),
                asset.getDeviceUid(), limited(asset.getName(), 100, "Unnamed printer"),
                limited(asset.getMachineNumber(), 50, null), limited(asset.getFirmwareType(), 32, "UNKNOWN"),
                canonicalStates == null || asset.getId() == null ? null : readCanonical(canonicalStates, asset.getId())))
                .toList();
    }

    private CanonicalPrinterModel readCanonical(CanonicalPrinterStateService states, long printerId) {
        try {
            return states.findLatestForCloud(printerId);
        } catch (CanonicalPrinterStateService.InvalidStoredModelException invalidModel) {
            long now = System.currentTimeMillis();
            if (invalidModelWarnings.size() >= 200 && !invalidModelWarnings.containsKey(printerId)) {
                invalidModelWarnings.clear();
            }
            invalidModelWarnings.compute(printerId, (id, last) -> {
                if (last == null || now - last >= Duration.ofMinutes(5).toMillis()) {
                    log.warn("Cloud canonical model is invalid; skipping printerId={}", id);
                    return now;
                }
                return last;
            });
            return null;
        }
    }

    public String sourceInstanceId() {
        if (jdbcTemplate == null) { throw new IllegalStateException("Cloud source identity is unavailable"); }
        return jdbcTemplate.queryForObject(
                "SELECT source_instance_id FROM farm_installation_identity WHERE id = 1", String.class);
    }

    public List<InventoryDevice> captureInventoryDevices(String sourceInstanceId) {
        java.util.UUID.fromString(sourceInstanceId);
        return assets().stream().map(asset -> {
            String localPrinterId = asset.getId() == null ? null : asset.getId().toString();
            if (localPrinterId == null || !localPrinterId.matches("[A-Za-z0-9._:-]{1,64}")) {
                throw new IllegalStateException("Cloud inventory contains an invalid local printer ID");
            }
            java.util.UUID.fromString(asset.getDeviceUid());
            String firmware = limited(asset.getFirmwareType(), 32, "UNKNOWN").toUpperCase(Locale.ROOT);
            String family = firmware.contains("RRF") || firmware.contains("REPRAP") ? "RRF"
                    : firmware.contains("KLIPPER") ? "KLIPPER" : null;
            if (family == null) { throw new IllegalStateException("Cloud inventory contains an unsupported firmware family"); }
            return new InventoryDevice(new InventoryIdentity(sourceInstanceId, asset.getDeviceUid(), localPrinterId),
                    new InventoryDescriptorSummary(limited(asset.getName(), 100, "Unnamed printer"),
                            limited(asset.getMachineNumber(), 50, null), family));
        }).toList();
    }

    private List<Printer> assets() {
        var result = printers.list(Wrappers.<Printer>lambdaQuery().orderByAsc(Printer::getId).last("LIMIT 201"));
        if (result.size() > 200) { throw new IllegalStateException("Cloud gateway currently supports at most 200 printers"); }
        return result;
    }

    public List<CloudWire.Printer> capture() {
        return capture(false);
    }

    public List<CloudWire.Printer> captureV2() {
        return capture(true);
    }

    private List<CloudWire.Printer> capture(boolean includeObservation) {
        var assets = printers.list(Wrappers.<Printer>lambdaQuery().orderByAsc(Printer::getId).last("LIMIT 201"));
        if (assets.size() > 200) { throw new IllegalStateException("Cloud gateway currently supports at most 200 printers"); }
        var result = new ArrayList<CloudWire.Printer>();
        boolean cacheAvailable = true;
        for (var asset : assets) {
            MoonrakerStatusDTO status = null;
            if (cacheAvailable) {
                try { status = cache.getCachedStatus(asset.getId()); }
                catch (RuntimeException unavailable) { cacheAvailable = false; }
            }
            String connection = normalized(asset.getConnectionStatus(), List.of("ONLINE", "OFFLINE", "UNKNOWN"));
            String state = normalized(PrinterVO.resolveEffectiveStatus(asset),
                    List.of("OFFLINE", "IDLE", "PREPARING", "PRINTING", "PAUSED", "ERROR", "UNKNOWN"));
            CloudWire.Observation observation = null;
            if (includeObservation && cacheAvailable) {
                try {
                    var view = cache.getObservationView(asset, Instant.now(), Duration.ofSeconds(30));
                    if (view != null && view.current() != null) observation = mapObservation(view.current());
                } catch (RuntimeException unavailable) {
                    cacheAvailable = false;
                }
            }
            result.add(new CloudWire.Printer(asset.getId().toString(), limited(asset.getName(), 100, "Unnamed printer"),
                    limited(asset.getFirmwareType(), 32, "UNKNOWN"), connection, state, Boolean.TRUE.equals(asset.getMonitorEnabled()),
                    asset.getLastSeenAt() == null ? null : asset.getLastSeenAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                    finite(status == null ? null : status.getProgress(), 0, 100),
                    finite(status == null ? null : status.getToolTemperature(), -100, 1000),
                    finite(status == null ? null : status.getBedTemperature(), -100, 1000), observation));
        }
        return List.copyOf(result);
    }

    private CloudWire.Observation mapObservation(PrinterObservation source) {
        var heaters = source.heaters().stream().map(h -> new CloudWire.Heater(h.id(), h.role().name(), h.mode().name(),
                measurement(h.currentC()), measurement(h.effectiveTargetC()), measurement(h.activeTargetC()),
                measurement(h.standbyTargetC()))).toList();
        var heaterIds = heaters.stream().map(CloudWire.Heater::id).collect(java.util.stream.Collectors.toSet());
        var tools = source.tools().stream().map(t -> new CloudWire.Tool(t.id(),
                t.heaterIds().stream().filter(heaterIds::contains).toList())).toList();
        var e = source.execution();
        var execution = e == null ? null : new CloudWire.Execution(e.simulation(), measurement(e.progressPercent()),
                measurement(e.printSeconds()), measurement(e.totalSeconds()), measurement(e.activeSeconds()),
                measurement(e.slicerEstimatedSeconds()), measurement(e.remainingSeconds()), measurement(e.filamentLengthMm()));
        return new CloudWire.Observation(source.captureStartedAt(), source.observedAt(), source.completeness().name(),
                source.systemState().name(), source.operationState().name(), heaters, tools, source.currentToolId(), execution);
    }

    private CloudWire.Measurement measurement(PrinterObservation.Measurement value) {
        return new CloudWire.Measurement(value.value(), value.quality().name(), value.observedAt());
    }

    private String normalized(String value, List<String> allowed) {
        String normalized = value == null ? "UNKNOWN" : value.toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "UNKNOWN";
    }
    private String limited(String value, int max, String fallback) {
        if (value == null || value.isBlank()) { return fallback; }
        return value.substring(0, Math.min(max, value.length()));
    }
    private Double finite(Double value, double min, double max) {
        return value == null || !Double.isFinite(value) || value < min || value > max ? null : value;
    }
}
