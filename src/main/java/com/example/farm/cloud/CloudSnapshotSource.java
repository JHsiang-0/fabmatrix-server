package com.example.farm.cloud;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterService;
import com.example.farm.protocol.observation.PrinterObservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "farm.cloud.enabled", havingValue = "true")
public class CloudSnapshotSource {
    private final PrinterService printers;
    private final PrinterCacheService cache;
    public CloudSnapshotSource(PrinterService printers, PrinterCacheService cache) { this.printers = printers; this.cache = cache; }

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
