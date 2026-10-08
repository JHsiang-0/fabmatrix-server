package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.config.PrinterRediscoveryProperties;
import com.example.farm.entity.Printer;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterNetworkDiscovery;
import com.example.farm.protocol.PrinterProtocolType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 防止 DHCP 地址复用后把另一台设备当作当前打印机；缺失 MAC 的旧记录保持兼容。 */
@Service
@RequiredArgsConstructor
public class PrinterIdentityVerifier {
    private static final long SUCCESS_TTL = Duration.ofSeconds(60).toNanos();
    private static final long FAILURE_TTL = Duration.ofSeconds(15).toNanos();
    private final PrinterNetworkDiscovery discovery;
    private final MacAddressUtil macAddressUtil;
    private final PrinterRediscoveryProperties properties;
    private final Map<Long, Verification> verified = new ConcurrentHashMap<>();

    public void verify(Printer printer) {
        verify(printer, false);
    }

    public void verifyFresh(Printer printer) {
        verify(printer, true);
    }

    private void verify(Printer printer, boolean force) {
        if (!properties.isEnabled() || printer == null || printer.getId() == null) {
            return;
        }
        String expectedMac = macAddressUtil.normalizeMacAddress(printer.getMacAddress());
        if (expectedMac == null) {
            return;
        }
        PrinterProtocolType protocol = PrinterProtocolType.normalize(printer.getFirmwareType());
        Fingerprint fingerprint = new Fingerprint(printer.getIpAddress(), expectedMac, protocol, printer.getApiKey());
        long now = System.nanoTime();
        Verification previous = verified.get(printer.getId());
        if (!force && previous != null && previous.fingerprint.equals(fingerprint) && now < previous.expiresAt) {
            if (!previous.matches) {
                throw identityFailure();
            }
            return;
        }
        PrinterNetworkDiscovery.Identity observed = discovery.identify(new PrinterEndpoint(printer.getId(),
                printer.getIpAddress(), "", protocol));
        boolean matches = observed != null && Objects.equals(fingerprint.address, observed.ipAddress())
                && protocol == observed.protocolType()
                && expectedMac.equals(macAddressUtil.normalizeMacAddress(observed.macAddress()));
        verified.put(printer.getId(), new Verification(fingerprint, matches,
                System.nanoTime() + (matches ? SUCCESS_TTL : FAILURE_TTL)));
        if (!matches) {
            throw identityFailure();
        }
    }

    public void invalidate(Long printerId) {
        if (printerId != null) {
            verified.remove(printerId);
        }
    }

    /** 通信中断后重新核对身份，避免重启期间的 DHCP 地址复用命中旧的成功缓存。 */
    public void onCommunicationFailure(Long printerId) {
        if (printerId != null) {
            verified.computeIfPresent(printerId, (id, verification) -> verification.matches ? null : verification);
        }
    }

    private BusinessException identityFailure() {
        return new BusinessException(503, "当前地址的设备身份无法确认，正在等待设备 IP 同步");
    }

    private record Fingerprint(String address, String mac, PrinterProtocolType protocol, String apiKey) {
        @Override
        public String toString() {
            return "PrinterIdentityFingerprint";
        }
    }
    private record Verification(Fingerprint fingerprint, boolean matches, long expiresAt) {
    }
}
