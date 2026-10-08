package com.example.farm.common.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MAC 地址获取工具类
 * <p>提供多种方式获取设备的 MAC 地址：</p>
 * <ul>
 *     <li>1. 通过系统 ARP 表查询（arp -a 命令）</li>
 *     <li>2. 通过 Moonraker/Klipper API 获取</li>
 * </ul>
 */
@Slf4j
@Component
public class MacAddressUtil {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String IPV4_TOKEN = "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}";
    private static final String MAC_TOKEN = "(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}";
    private static final Pattern ARP_NUMERIC_ROW = Pattern.compile(
            "^\\s*(" + IPV4_TOKEN + ")\\s+(?:ether\\s+)?(" + MAC_TOKEN + ")(?:\\s|$)");
    private static final Pattern ARP_NAMED_ROW = Pattern.compile(
            "^\\s*[^\\s(]*\\s*\\((" + IPV4_TOKEN + ")\\)\\s+at\\s+(" + MAC_TOKEN + ")(?:\\s|$)");
    private static final Pattern MAC_PATTERN = Pattern.compile(
            "(?:[0-9A-Fa-f]{12}|[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}|[0-9A-Fa-f]{2}(?:-[0-9A-Fa-f]{2}){5})");
    private static final int MAX_ARP_OUTPUT_BYTES = 64 * 1024;
    private static final Duration API_TIMEOUT = Duration.ofSeconds(3);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(API_TIMEOUT).build();

    /**
     * 尝试获取指定 IP 对应的 MAC 地址
     * <p>优先使用 ARP 表查询，失败则尝试 Moonraker API</p>
     *
     * @param ipAddress 目标 IP 地址
     * @return MAC 地址（标准格式如 00:11:22:33:44:55），获取失败返回 null
     */
    public String getMacAddress(String ipAddress) {
        // 首先尝试从 ARP 表获取
        String macFromArp = getMacFromArpTable(ipAddress);
        if (macFromArp != null) {
            log.debug("从 ARP 表获取到 MAC 地址: IP={}, MAC={}", ipAddress, macFromArp);
            return macFromArp;
        }

        // 如果 ARP 表没有，尝试通过 Moonraker API 获取
        String macFromApi = getMacFromMoonrakerApi(ipAddress);
        if (macFromApi != null) {
            log.debug("从 Moonraker API 获取到 MAC 地址: IP={}, MAC={}", ipAddress, macFromApi);
            return macFromApi;
        }

        log.warn("无法获取设备 MAC 地址: IP={}", ipAddress);
        return null;
    }

    /**
     * 从系统 ARP 表中查询指定 IP 的 MAC 地址
     *
     * @param ipAddress 目标 IP 地址
     * @return MAC 地址，未找到返回 null
     */
    public String getMacFromArpTable(String ipAddress) {
        if (!isIpv4Address(ipAddress) || Thread.currentThread().isInterrupted()) {
            return null;
        }
        Process process = null;
        try {
            // 先执行 ping 确保 ARP 表中有记录
            pingHost(ipAddress);
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }

            // 执行 arp -a 命令获取 ARP 表
            process = new ProcessBuilder("arp", "-a", ipAddress).redirectErrorStream(true).start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);

            if (!finished) {
                log.warn("ARP 命令执行超时: IP={}", ipAddress);
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (var output = process.getInputStream()) {
                byte[] bytes = output.readNBytes(MAX_ARP_OUTPUT_BYTES + 1);
                return bytes.length > MAX_ARP_OUTPUT_BYTES ? null
                        : extractMacFromArpOutput(ipAddress, new String(bytes, Charset.defaultCharset()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("从 ARP 表获取 MAC 地址失败: IP={}, 原因={}", ipAddress, e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return null;
    }

    /** 仅接受目标 IP 所在的数据行；页眉、网关、相似 IP 和多接口冲突均不能用作身份。 */
    String extractMacFromArpOutput(String ipAddress, String output) {
        if (!isIpv4Address(ipAddress) || output == null) {
            return null;
        }
        String result = null;
        for (String line : output.split("\\R")) {
            Matcher row = ARP_NUMERIC_ROW.matcher(line);
            if (!row.find()) {
                row = ARP_NAMED_ROW.matcher(line);
                if (!row.find()) {
                    continue;
                }
            }
            if (!ipAddress.equals(row.group(1))) {
                continue;
            }
            String mac = normalizeMacAddress(row.group(2));
            if (mac != null) {
                if (result != null && !result.equals(mac)) {
                    return null;
                }
                result = mac;
            }
        }
        return result;
    }

    /**
     * 通过 Moonraker API 获取 MAC 地址
     * <p>调用 /machine/system_info 接口获取网络信息</p>
     *
     * @param ipAddress 目标 IP 地址
     * @return MAC 地址，获取失败返回 null
     */
    public String getMacFromMoonrakerApi(String ipAddress) {
        return getMacFromMoonrakerApi(ipAddress, null);
    }

    /** 已登记设备可以使用它自己的 API Key；未知地址探测应传空 Key。 */
    public String getMacFromMoonrakerApi(String ipAddress, String apiKey) {
        if (!isIpv4Address(ipAddress) || Thread.currentThread().isInterrupted()) {
            return null;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + ipAddress + ":7125/machine/system_info"))
                    .GET()
                    .timeout(API_TIMEOUT);
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("X-Api-Key", apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                return extractMacFromMoonrakerResponse(ipAddress, mapper.readTree(response.body()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("从 Moonraker API 获取 MAC 地址失败: IP={}, 类型={}", ipAddress, e.getClass().getSimpleName());
        }
        return null;
    }

    String extractMacFromMoonrakerResponse(String ipAddress, JsonNode response) {
        if (!isIpv4Address(ipAddress) || response == null) {
            return null;
        }
        JsonNode network = response.path("result").path("system_info").path("network");
        if (!network.isObject()) {
            return null;
        }
        String result = null;
        for (JsonNode networkInterface : network) {
            boolean targetInterface = ipAddress.equals(networkInterface.path("ip_address").asText());
            JsonNode addresses = networkInterface.path("ip_addresses");
            if (addresses.isArray()) {
                for (JsonNode address : addresses) {
                    if (ipAddress.equals(address.isTextual() ? address.asText() : address.path("address").asText())) {
                        targetInterface = true;
                    }
                }
            }
            if (!targetInterface) {
                continue;
            }
            String mac = normalizeMacAddress(networkInterface.path("mac_address").asText(null));
            if (mac != null) {
                if (result != null && !result.equals(mac)) {
                    return null;
                }
                result = mac;
            }
        }
        return result;
    }

    /**
     * 发送 ICMP ping 包给目标主机，确保 ARP 表中有记录
     *
     * @param ipAddress 目标 IP 地址
     */
    private void pingHost(String ipAddress) {
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            address.isReachable(500);
        } catch (Exception e) {
            log.debug("Ping 主机失败: IP={}", ipAddress);
        }
    }

    /**
     * 标准化 MAC 地址格式
     * <p>将各种格式统一转换为冒号分隔的小写格式（如 00:11:22:33:44:55）</p>
     *
     * @param macAddress 原始 MAC 地址
     * @return 标准化后的 MAC 地址
     */
    public String normalizeMacAddress(String macAddress) {
        return normalizeDeviceMacAddress(macAddress);
    }

    /** 无网络副作用的设备身份格式规范化，供持久化校验复用。 */
    public static String normalizeDeviceMacAddress(String macAddress) {
        if (macAddress == null || macAddress.trim().isEmpty()) {
            return null;
        }

        String trimmed = macAddress.trim();
        if (!MAC_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        String cleanMac = trimmed.replace(":", "").replace("-", "").toLowerCase(Locale.ROOT);
        // 零地址、广播地址与组播地址不是可持久化的设备身份。
        if ("000000000000".equals(cleanMac) || "ffffffffffff".equals(cleanMac)
                || (Integer.parseInt(cleanMac.substring(0, 2), 16) & 1) != 0) {
            return null;
        }

        // 重新格式化为 xx:xx:xx:xx:xx:xx
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                formatted.append(':');
            }
            formatted.append(cleanMac, i, i + 2);
        }

        return formatted.toString();
    }

    /** 严格 IPv4 字面量校验，禁止将主机名或命令参数当作地址执行。 */
    public static boolean isIpv4Address(String ipAddress) {
        if (ipAddress == null || !ipAddress.matches(IPV4_TOKEN)) {
            return false;
        }
        for (String octet : ipAddress.split("\\.")) {
            if ((octet.length() > 1 && octet.charAt(0) == '0') || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取本机指定网络接口的 MAC 地址
     *
     * @param interfaceName 网络接口名称（如 eth0, wlan0）
     * @return MAC 地址，获取失败返回 null
     */
    public String getLocalMacAddress(String interfaceName) {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
            if (networkInterface == null) {
                return null;
            }

            byte[] macBytes = networkInterface.getHardwareAddress();
            if (macBytes == null) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < macBytes.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02x", macBytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("获取本机 MAC 地址失败: interface={}, 原因={}", interfaceName, e.getMessage());
            return null;
        }
    }

    /**
     * 生成默认打印机名称
     * <p>基于 MAC 地址后四位生成唯一名称</p>
     *
     * @param macAddress MAC 地址
     * @return 默认打印机名称（如 Printer_A1B2）
     */
    public String generateDefaultPrinterName(String macAddress) {
        String normalizedMac = normalizeMacAddress(macAddress);
        if (normalizedMac == null) {
            return "Printer_Unknown";
        }

        // 提取后 4 位（最后两个十六进制数）
        String lastFour = normalizedMac.substring(normalizedMac.length() - 5).replace(":", "");
        return "Printer_" + lastFour.toUpperCase();
    }

    /**
     * 验证 MAC 地址格式是否有效
     *
     * @param macAddress MAC 地址
     * @return 是否有效
     */
    public boolean isValidMacAddress(String macAddress) {
        if (macAddress == null || macAddress.trim().isEmpty()) {
            return false;
        }
        String normalized = normalizeMacAddress(macAddress);
        return normalized != null && normalized.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}");
    }
}
