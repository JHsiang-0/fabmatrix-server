package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.config.PrinterMonitorProperties;
import com.example.farm.config.PrinterRediscoveryProperties;
import com.example.farm.entity.Printer;
import com.example.farm.entity.vo.PrinterIpSyncItemVO;
import com.example.farm.entity.vo.PrinterRediscoveryResultVO;
import com.example.farm.protocol.PrinterNetworkDiscovery;
import com.example.farm.protocol.PrinterProtocolType;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** 合并全农场重新发现：只恢复已有可信身份，设备操作与地址同步分离。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PrinterRediscoveryService {
    private static final String LOCK_KEY = "farm:printer:address-rediscovery";
    private final PrinterService printerService;
    private final PrinterNetworkDiscovery discovery;
    private final PrinterAddressUpdateService addressUpdateService;
    private final MacAddressUtil macAddressUtil;
    private final PrinterRediscoveryProperties properties;
    private final PrinterMonitorProperties monitorProperties;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "printer-address-rediscovery");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(action -> {
        Thread thread = new Thread(action, "printer-rediscovery-lock-renewal");
        thread.setDaemon(true);
        return thread;
    });
    @Autowired(required = false)
    private RedisUtil redisUtil;
    @Autowired(required = false)
    private PrinterIdentityVerifier identityVerifier;

    private volatile PrinterRediscoveryResultVO latest = new PrinterRediscoveryResultVO(null, "IDLE",
            "尚未同步设备 IP", null, null, List.of(), 0, 0, 0, 0, List.of());
    private long lastAutomaticStart;

    public PrinterRediscoveryResultVO getLatest() {
        requireAdmin();
        return latest;
    }

    public synchronized PrinterRediscoveryResultVO startManual() {
        requireAdmin();
        return start();
    }

    public synchronized void startAutomatic() {
        if (!properties.isEnabled() || !monitorProperties.isEnabled() || worker.isShutdown()) {
            return;
        }
        long now = System.nanoTime();
        Duration interval = properties.getInterval();
        if (interval == null || interval.compareTo(Duration.ofSeconds(30)) < 0) {
            log.warn("设备地址自动恢复未启动：轮询间隔至少应为30秒");
            return;
        }
        if (lastAutomaticStart != 0 && now - lastAutomaticStart < interval.toNanos()) {
            return;
        }
        if (!"RUNNING".equals(latest.status())) {
            lastAutomaticStart = now;
            start();
        }
    }

    private PrinterRediscoveryResultVO start() {
        if (worker.isShutdown()) {
            throw new BusinessException(503, "服务正在关闭，请稍后重试");
        }
        if ("RUNNING".equals(latest.status())) {
            return latest;
        }
        String scanId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now();
        PrinterRediscoveryResultVO started = snapshot(scanId, "RUNNING", "正在搜索已登记设备的新地址",
                startedAt, null, List.of(), 0, List.of());
        latest = started;
        worker.execute(() -> run(scanId, startedAt));
        return started;
    }

    private void run(String scanId, LocalDateTime startedAt) {
        String lockValue = UUID.randomUUID().toString();
        ScheduledFuture<?> renewal = null;
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        boolean locked = false;
        List<String> subnets = List.of();
        List<PrinterIpSyncItemVO> items = new ArrayList<>();
        try {
            if (redisUtil != null) {
                locked = redisUtil.tryLock(LOCK_KEY, lockValue, 360, TimeUnit.SECONDS);
                if (!locked) {
                    throw new BusinessException(409, "另一服务实例正在同步设备 IP，请稍后重试");
                }
                renewal = renewer.scheduleAtFixedRate(() -> {
                    try {
                        if (!redisUtil.renewLockIfOwned(LOCK_KEY, lockValue, 360, TimeUnit.SECONDS)) {
                            leaseValid.set(false);
                        }
                    } catch (RuntimeException exception) {
                        leaseValid.set(false);
                    }
                }, 15, 15, TimeUnit.SECONDS);
            }
            List<Printer> all = printerService.list().stream().filter(printer -> printer.getId() != null)
                    .sorted(Comparator.comparing(Printer::getId)).toList();
            List<Long> whitelist = monitorProperties.getPrinterIds();
            List<Printer> targets = all.stream().filter(printer -> Boolean.TRUE.equals(printer.getMonitorEnabled()))
                    .filter(printer -> whitelist == null || whitelist.isEmpty() || whitelist.contains(printer.getId()))
                    .toList();
            subnets = resolveSubnets(targets);
            latest = snapshot(scanId, "RUNNING", "正在搜索已登记设备的新地址", startedAt, null,
                    subnets, 0, List.of());
            boolean hasIdentity = targets.stream().anyMatch(printer -> normalizedMac(printer) != null && supportedProtocol(printer));
            List<PrinterNetworkDiscovery.Identity> found = hasIdentity && !subnets.isEmpty()
                    ? discovery.discover(subnets, properties.getConcurrency(), properties.getTimeout()) : List.of();
            Set<String> allowed = Set.copyOf(subnets);
            if (found.stream().anyMatch(identity -> !allowed.contains(privateSubnet(identity.ipAddress())))) {
                throw new BusinessException(409, "发现结果超出配置的厂内网段，未更新设备地址");
            }
            Map<String, List<Printer>> knownByMac = all.stream().filter(printer -> normalizedMac(printer) != null)
                    .collect(Collectors.groupingBy(this::normalizedMac));
            Map<String, List<PrinterNetworkDiscovery.Identity>> foundByMac = found.stream()
                    .filter(identity -> macAddressUtil.normalizeMacAddress(identity.macAddress()) != null)
                    .collect(Collectors.groupingBy(identity -> macAddressUtil.normalizeMacAddress(identity.macAddress())));
            List<PrinterAddressUpdateService.Change> changes = new ArrayList<>();
            for (Printer printer : targets) {
                String mac = normalizedMac(printer);
                if (mac == null) {
                    items.add(item(printer, null, "MISSING_MAC", "设备未登记有效 MAC，请先扫描并确认设备身份"));
                    continue;
                }
                PrinterProtocolType protocol;
                try {
                    protocol = PrinterProtocolType.normalize(printer.getFirmwareType());
                } catch (BusinessException exception) {
                    items.add(item(printer, null, "UNSUPPORTED_PROTOCOL", "设备协议不支持自动地址恢复"));
                    continue;
                }
                List<PrinterNetworkDiscovery.Identity> matches = foundByMac.getOrDefault(mac, List.of());
                if (knownByMac.get(mac).size() != 1 || matches.size() > 1) {
                    items.add(item(printer, null, "CONFLICT", "设备身份对应多条记录或多个地址，需人工核对"));
                } else if (matches.isEmpty()) {
                    items.add(item(printer, null, "NOT_FOUND", subnets.isEmpty()
                            ? "无法确定厂内网段，请管理员配置搜索范围" : "尚未发现设备，请确认已开机并连接厂内网络"));
                } else if (matches.getFirst().protocolType() != protocol) {
                    items.add(item(printer, null, "CONFLICT", "发现设备的协议与登记信息不一致，未更新地址"));
                } else {
                    String address = matches.getFirst().ipAddress();
                    if (address.equals(printer.getIpAddress())) {
                        items.add(item(printer, address, "UNCHANGED", "设备身份已确认，IP 未变化"));
                    } else {
                        changes.add(new PrinterAddressUpdateService.Change(printer.getId(), mac, protocol.name(),
                                printer.getIpAddress(), address));
                        items.add(item(printer, address, "UPDATED", "设备 IP 已同步，等待监控确认在线状态"));
                    }
                }
            }
            if (!leaseValid.get() || Thread.currentThread().isInterrupted()) {
                throw new BusinessException(409, "设备同步锁已失效，未更新设备地址");
            }
            List<Long> updated = addressUpdateService.apply(changes);
            if (identityVerifier != null) {
                for (Long id : updated) {
                    try {
                        identityVerifier.invalidate(id);
                    } catch (RuntimeException exception) {
                        log.warn("设备地址已提交，身份缓存清理失败: printerId={}", id);
                    }
                }
            }
            latest = snapshot(scanId, "COMPLETED", "设备 IP 同步完成", startedAt, LocalDateTime.now(),
                    subnets, hasIdentity ? subnets.size() * 254 : 0, items);
            log.info("设备地址同步完成: scanId={}, subnets={}, updated={}, unresolved={}",
                    scanId, subnets.size(), latest.updatedCount(), latest.unresolvedCount());
        } catch (Exception exception) {
            String message = exception instanceof BusinessException business ? business.getMessage()
                    : "设备 IP 同步失败，请稍后重试或查看服务日志";
            List<PrinterIpSyncItemVO> failedItems = items.stream().map(item -> "UPDATED".equals(item.status())
                    ? new PrinterIpSyncItemVO(item.printerId(), item.printerName(), item.oldIpAddress(),
                    item.newIpAddress(), "CONFLICT", "地址更新未提交，请重新同步") : item).toList();
            latest = snapshot(scanId, "FAILED", message, startedAt, LocalDateTime.now(), subnets, 0, failedItems);
            log.warn("设备地址同步失败: scanId={}, category={}", scanId, exception.getClass().getSimpleName());
        } finally {
            if (renewal != null) {
                renewal.cancel(false);
            }
            if (locked) {
                try {
                    redisUtil.releaseLockIfOwned(LOCK_KEY, lockValue);
                } catch (RuntimeException exception) {
                    log.warn("设备地址同步锁清理失败: scanId={}", scanId);
                }
            }
        }
    }

    private List<String> resolveSubnets(List<Printer> targets) {
        List<String> configured = properties.getSubnets();
        List<String> result;
        if (configured != null && !configured.isEmpty()) {
            result = configured.stream().map(prefix -> {
                if (prefix == null || !prefix.trim().equals(privateSubnet(prefix.trim() + ".1"))) {
                    throw new BusinessException(400, "搜索范围必须是厂内私有 IPv4 的三段网段前缀");
                }
                return prefix.trim();
            }).distinct().toList();
        } else {
            result = targets.stream().filter(printer -> normalizedMac(printer) != null && supportedProtocol(printer))
                    .map(printer -> privateSubnet(printer.getIpAddress())).filter(java.util.Objects::nonNull)
                    .distinct().sorted().toList();
        }
        if (result.size() > 8) {
            throw new BusinessException(400, "单次最多搜索8个网段，请管理员限定厂内搜索范围");
        }
        return result;
    }

    static String privateSubnet(String address) {
        if (!StringUtils.hasText(address)) {
            return null;
        }
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            try {
                if (!parts[index].matches("0|[1-9][0-9]{0,2}")) {
                    return null;
                }
                octets[index] = Integer.parseInt(parts[index]);
                if (octets[index] > 255) {
                    return null;
                }
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        if (octets[3] == 0 || octets[3] == 255 || !(octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168))) {
            return null;
        }
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private String normalizedMac(Printer printer) {
        return macAddressUtil.normalizeMacAddress(printer.getMacAddress());
    }

    private boolean supportedProtocol(Printer printer) {
        try {
            PrinterProtocolType.normalize(printer.getFirmwareType());
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private PrinterIpSyncItemVO item(Printer printer, String address, String status, String message) {
        return new PrinterIpSyncItemVO(printer.getId(), printer.getName(), printer.getIpAddress(), address, status, message);
    }

    private PrinterRediscoveryResultVO snapshot(String scanId, String status, String message, LocalDateTime started,
                                               LocalDateTime finished, List<String> subnets, int scanned,
                                               List<PrinterIpSyncItemVO> items) {
        int updated = (int) items.stream().filter(item -> "UPDATED".equals(item.status())).count();
        int unchanged = (int) items.stream().filter(item -> "UNCHANGED".equals(item.status())).count();
        return new PrinterRediscoveryResultVO(scanId, status, message, started, finished, subnets, scanned,
                updated, unchanged, items.size() - updated - unchanged, items);
    }

    private void requireAdmin() {
        if (!SecurityContextUtil.isAdmin()) {
            throw new BusinessException(403, "只有管理员可以同步或查看设备 IP");
        }
    }

    @PreDestroy
    public synchronized void destroy() {
        worker.shutdownNow();
        renewer.shutdownNow();
    }
}
