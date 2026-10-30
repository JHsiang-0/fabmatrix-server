package com.example.farm.cloud;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
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
            result.add(new CloudWire.Printer(asset.getId().toString(), limited(asset.getName(), 100, "Unnamed printer"),
                    limited(asset.getFirmwareType(), 32, "UNKNOWN"), connection, state, Boolean.TRUE.equals(asset.getMonitorEnabled()),
                    asset.getLastSeenAt() == null ? null : asset.getLastSeenAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                    finite(status == null ? null : status.getProgress(), 0, 100),
                    finite(status == null ? null : status.getToolTemperature(), -100, 1000),
                    finite(status == null ? null : status.getBedTemperature(), -100, 1000)));
        }
        return List.copyOf(result);
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
