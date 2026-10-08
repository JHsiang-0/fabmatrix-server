package com.example.farm.protocol;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 只读局域网设备身份发现，不导入设备、不发出打印或配置命令。
 *
 * <p>仅扫描显式给出的 /24 前缀。发现未知 IP 时不使用任何已登记设备的密钥，
 * 只有协议可识别且 MAC 属于目标 IP 的设备才能成为候选。总截止时间到达时
 * 整次发现失败，调用方不能把部分扫描结果当成完整的地址交换证据。</p>
 */
@Component
public class PrinterNetworkDiscovery {

    private static final int MAX_SUBNETS = 8;
    private static final int MAX_CONCURRENCY = 64;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private final PrinterProtocolDetector protocolDetector;
    private final RrfApiClient rrfApiClient;
    private final MacAddressUtil macAddressUtil;
    private final ReentrantLock scanLock = new ReentrantLock();
    // 超时后若网络库尚未响应中断，拒绝开启新线程池，保持总线程数有界。
    private ExecutorService previousExecutor;

    public PrinterNetworkDiscovery(PrinterProtocolDetector protocolDetector, RrfApiClient rrfApiClient,
                                   MacAddressUtil macAddressUtil) {
        this.protocolDetector = protocolDetector;
        this.rrfApiClient = rrfApiClient;
        this.macAddressUtil = macAddressUtil;
    }

    public record Identity(String ipAddress, String macAddress, PrinterProtocolType protocolType) {
    }

    public List<Identity> discover(List<String> subnetPrefixes, int concurrency, Duration timeout) {
        List<String> targets = scanTargets(subnetPrefixes, concurrency, timeout);
        if (!scanLock.tryLock()) {
            throw new BusinessException(503, "设备 IP 发现正在运行，请稍后重试");
        }
        ExecutorService executor = null;
        List<Future<Identity>> submitted = new ArrayList<>();
        try {
            if (previousExecutor != null && !previousExecutor.isTerminated()) {
                throw new BusinessException(503, "上次设备 IP 发现尚未停止，请稍后重试");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new BusinessException(503, "设备 IP 发现已被中断");
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            AtomicInteger threadNumber = new AtomicInteger();
            executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(concurrency), task -> {
                Thread thread = new Thread(task, "printer-ip-discovery-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            previousExecutor = executor;
            ExecutorCompletionService<Identity> completion = new ExecutorCompletionService<>(executor);
            int nextTarget = 0;
            int inFlight = 0;
            while (nextTarget < targets.size() && inFlight < concurrency) {
                String ipAddress = targets.get(nextTarget++);
                submitted.add(completion.submit(() -> discoverAddress(ipAddress)));
                inFlight++;
            }

            List<Identity> identities = new ArrayList<>();
            while (inFlight > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw timeoutFailure();
                }
                Future<Identity> finished = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (finished == null || deadline - System.nanoTime() <= 0) {
                    throw timeoutFailure();
                }
                Identity identity = finished.get();
                inFlight--;
                if (identity != null) {
                    identities.add(identity);
                }
                if (nextTarget < targets.size()) {
                    String ipAddress = targets.get(nextTarget++);
                    submitted.add(completion.submit(() -> discoverAddress(ipAddress)));
                    inFlight++;
                }
            }
            if (deadline - System.nanoTime() <= 0) {
                throw timeoutFailure();
            }
            identities.sort(Comparator.comparingLong(identity -> ipv4Number(identity.ipAddress())));
            return List.copyOf(identities);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "设备 IP 发现已被中断");
        } catch (ExecutionException exception) {
            throw new BusinessException(503, "设备 IP 发现执行失败，请稍后重试");
        } finally {
            for (Future<Identity> future : submitted) {
                future.cancel(true);
            }
            if (executor != null) {
                executor.shutdownNow();
            }
            scanLock.unlock();
        }
    }

    /**
     * 匿名核对当前地址的身份；即使 endpoint 带密钥也不发送，防止 DHCP 复用泄露。
     * API 身份缺失时可读取精确目标 IP 的 ARP 记录；仍无法确认则返回 null。
     */
    public Identity identify(PrinterEndpoint endpoint) {
        if (endpoint == null || !MacAddressUtil.isIpv4Address(endpoint.ipAddress())
                || endpoint.protocolType() == null || Thread.currentThread().isInterrupted()) {
            return null;
        }
        PrinterEndpoint anonymousEndpoint = new PrinterEndpoint(endpoint.printerId(), endpoint.ipAddress(),
                "", endpoint.protocolType());
        String macAddress = null;
        try {
            if (endpoint.protocolType() == PrinterProtocolType.RRF) {
                RrfApiClient.RrfNetworkIdentity identity = rrfApiClient.getNetworkIdentity(anonymousEndpoint);
                if (identity != null && endpoint.ipAddress().equals(identity.actualIp())) {
                    macAddress = macAddressUtil.normalizeMacAddress(identity.macAddress());
                }
            } else {
                macAddress = macAddressUtil.normalizeMacAddress(
                        macAddressUtil.getMacFromMoonrakerApi(endpoint.ipAddress(), ""));
            }
        } catch (RuntimeException ignored) {
            // 身份读取失败时仅允许精确目标的 ARP 兜底，不记录 endpoint 中的密钥。
        }
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        if (macAddress == null) {
            macAddress = macAddressUtil.normalizeMacAddress(macAddressUtil.getMacFromArpTable(endpoint.ipAddress()));
        }
        return macAddress == null ? null : new Identity(endpoint.ipAddress(), macAddress, endpoint.protocolType());
    }

    private Identity discoverAddress(String ipAddress) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        PrinterProtocolType protocolType = protocolDetector.detect(ipAddress);
        if (protocolType == null || Thread.currentThread().isInterrupted()) {
            return null;
        }
        return identify(new PrinterEndpoint(null, ipAddress, "", protocolType));
    }

    private List<String> scanTargets(List<String> subnetPrefixes, int concurrency, Duration timeout) {
        if (subnetPrefixes == null || subnetPrefixes.isEmpty() || subnetPrefixes.size() > MAX_SUBNETS
                || concurrency < 1 || concurrency > MAX_CONCURRENCY || timeout == null
                || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new BusinessException(422, "设备 IP 发现的网段、并发数或超时配置不合法");
        }
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        for (String prefix : subnetPrefixes) {
            if (prefix == null || !prefix.matches("(?:[0-9]{1,3}\\.){2}[0-9]{1,3}")
                    || !MacAddressUtil.isIpv4Address(prefix + ".1")) {
                throw new BusinessException(422, "设备 IP 发现网段必须是 IPv4 前三段，如 192.168.1");
            }
            int firstOctet = Integer.parseInt(prefix.substring(0, prefix.indexOf('.')));
            if (firstOctet == 0 || firstOctet == 127 || firstOctet >= 224) {
                throw new BusinessException(422, "设备 IP 发现网段不能使用本机、广播或组播地址");
            }
            prefixes.add(prefix);
        }
        List<String> targets = new ArrayList<>(prefixes.size() * 254);
        for (String prefix : prefixes) {
            for (int host = 1; host <= 254; host++) {
                targets.add(prefix + "." + host);
            }
        }
        return targets;
    }

    private long ipv4Number(String address) {
        long result = 0;
        for (String octet : address.split("\\.")) {
            result = (result << 8) | Integer.parseInt(octet);
        }
        return result;
    }

    private BusinessException timeoutFailure() {
        return new BusinessException(503, "设备 IP 发现超时，未应用扫描结果，请稍后重试");
    }
}
