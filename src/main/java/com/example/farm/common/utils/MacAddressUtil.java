package com.example.farm.common.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
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

    // MAC 地址正则表达式（支持多种格式）
    private static final Pattern MAC_PATTERN = Pattern.compile(
            "([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})"
    );

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
        try {
            // 先执行 ping 确保 ARP 表中有记录
            pingHost(ipAddress);

            // 执行 arp -a 命令获取 ARP 表
            Process process = Runtime.getRuntime().exec("arp -a " + ipAddress);
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.warn("ARP 命令执行超时: IP={}", ipAddress);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("ARP 输出: {}", line);
                    String mac = extractMacFromString(line);
                    if (mac != null) {
                        return normalizeMacAddress(mac);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("从 ARP 表获取 MAC 地址失败: IP={}, 原因={}", ipAddress, e.getMessage());
        }
        return null;
    }

    /**
     * 通过 Moonraker API 获取 MAC 地址
     * <p>调用 /machine/system_info 接口获取网络信息</p>
     *
     * @param ipAddress 目标 IP 地址
     * @return MAC 地址，获取失败返回 null
     */
    public String getMacFromMoonrakerApi(String ipAddress) {
        try {
            String url = String.format("http://%s:7125/machine/system_info", ipAddress);

            // 使用简单的 HTTP 请求获取 JSON 响应
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(3))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                JsonNode rootNode = mapper.readTree(response.body());
                JsonNode resultNode = rootNode.path("result");

                if (resultNode.has("system_info") && resultNode.path("system_info").has("network")) {
                    JsonNode networkNode = resultNode.path("system_info").path("network");

                    // 遍历所有网络接口查找匹配的 IP
                    if (networkNode.isObject()) {
                        var fields = networkNode.fields();
                        while (fields.hasNext()) {
                            var entry = fields.next();
                            JsonNode ifaceNode = entry.getValue();

                            if (ifaceNode.has("ip_address")) {
                                String ifaceIp = ifaceNode.path("ip_address").asText();
                                if (ipAddress.equals(ifaceIp) && ifaceNode.has("mac_address")) {
                                    String mac = ifaceNode.path("mac_address").asText();
                                    return normalizeMacAddress(mac);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从 Moonraker API 获取 MAC 地址失败: IP={}, 原因={}", ipAddress, e.getMessage());
        }
        return null;
    }

    /**
     * 发送 ICMP ping 包给目标主机，确保 ARP 表中有记录
     *
     * @param ipAddress 目标 IP 地址
     */
    private void pingHost(String ipAddress) {
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            address.isReachable(1000); // 1秒超时
        } catch (Exception e) {
            log.debug("Ping 主机失败: IP={}", ipAddress);
        }
    }

    /**
     * 从字符串中提取 MAC 地址
     *
     * @param input 输入字符串
     * @return 提取到的 MAC 地址，未找到返回 null
     */
    private String extractMacFromString(String input) {
        Matcher matcher = MAC_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    /**
     * 标准化 MAC 地址格式
     * <p>将各种格式统一转换为冒号分隔的小写格式（如 00:11:22:33:44:55）</p>
     *
     * @param macAddress 原始 MAC 地址
     * @return 标准化后的 MAC 地址
     */
    public String normalizeMacAddress(String macAddress) {
        if (macAddress == null || macAddress.trim().isEmpty()) {
            return null;
        }

        // 移除所有分隔符，只保留十六进制字符
        String cleanMac = macAddress.replaceAll("[^0-9A-Fa-f]", "").toLowerCase();

        // 验证长度是否为 12（6 字节 * 2 个十六进制字符）
        if (cleanMac.length() != 12) {
            log.warn("无效的 MAC 地址格式: {}", macAddress);
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