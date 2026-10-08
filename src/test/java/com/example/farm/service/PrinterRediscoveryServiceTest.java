package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.config.PrinterMonitorProperties;
import com.example.farm.config.PrinterRediscoveryProperties;
import com.example.farm.entity.Printer;
import com.example.farm.entity.vo.PrinterRediscoveryResultVO;
import com.example.farm.protocol.PrinterNetworkDiscovery;
import com.example.farm.protocol.PrinterNetworkDiscovery.Identity;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.service.PrinterAddressUpdateService.Change;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Only immutable identity fixtures are scanned; no database, Redis or network clients run. */
class PrinterRediscoveryServiceTest {

    private PrinterService printers;
    private PrinterNetworkDiscovery discovery;
    private PrinterAddressUpdateService updater;
    private MacAddressUtil macUtil;
    private PrinterRediscoveryProperties properties;
    private PrinterMonitorProperties monitorProperties;
    private PrinterRediscoveryService service;

    @BeforeEach
    void setUp() {
        printers = mock(PrinterService.class);
        discovery = mock(PrinterNetworkDiscovery.class);
        updater = mock(PrinterAddressUpdateService.class);
        macUtil = new MacAddressUtil();
        properties = new PrinterRediscoveryProperties();
        monitorProperties = new PrinterMonitorProperties();
        service = new PrinterRediscoveryService(printers, discovery, updater, macUtil, properties, monitorProperties);
        when(printers.list()).thenReturn(List.of());
        when(discovery.discover(anyList(), anyInt(), any())).thenReturn(List.of());
        when(updater.apply(anyList())).thenAnswer(invocation -> invocation.<List<Change>>getArgument(0).stream()
                .map(Change::printerId).sorted().toList());
        authenticate("ADMIN");
    }

    @AfterEach
    void tearDown() {
        service.destroy();
        SecurityContextHolder.clearContext();
    }

    @Test
    void manualStartAndStatusEnforceAdminAtTheServiceBoundary() {
        for (String role : new String[] {null, "OPERATOR"}) {
            authenticate(role);
            assertThatThrownBy(service::startManual).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo(403));
            assertThatThrownBy(service::getLatest).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo(403));
        }
        verifyNoInteractions(printers, discovery, updater);
    }

    @Test
    void asynchronousCompletionUpdatesKnownIdentityAndIgnoresUnknownAndDisabledDevices() {
        Printer first = printer(1, "192.168.10.1", "klipper");
        Printer second = printer(2, "192.168.10.2", "RRF");
        Printer disabled = printer(3, "192.168.10.3", "RRF");
        disabled.setMonitorEnabled(false);
        when(printers.list()).thenReturn(List.of(disabled, second, first));
        when(discovery.discover(anyList(), anyInt(), any())).thenReturn(List.of(
                identity(1, "192.168.10.21", PrinterProtocolType.KLIPPER),
                identity(2, "192.168.10.2", PrinterProtocolType.RRF),
                identity(3, "192.168.10.23", PrinterProtocolType.RRF),
                identity(99, "192.168.10.99", PrinterProtocolType.KLIPPER)));

        PrinterRediscoveryResultVO started = service.startManual();
        PrinterRediscoveryResultVO finished = awaitFinished();

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(finished.scanId()).isEqualTo(started.scanId());
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.startedAt()).isNotNull();
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(finished.updatedCount()).isEqualTo(1);
        assertThat(finished.unchangedCount()).isEqualTo(1);
        assertThat(finished.unresolvedCount()).isZero();
        assertThat(finished.scannedAddressCount()).isEqualTo(254);
        assertThat(finished.items()).extracting(item -> item.printerId()).containsExactly(1L, 2L);
        verify(updater).apply(List.of(new Change(1L, canonicalMac(1), "KLIPPER",
                "192.168.10.1", "192.168.10.21")));
        verify(discovery).discover(List.of("192.168.10"), properties.getConcurrency(), properties.getTimeout());
        verify(printers).list();
        verifyNoMoreInteractions(printers);
        assertThat(first.getIpAddress()).isEqualTo("192.168.10.1");
    }

    @Test
    void missingMacAddressesAreReportedWithoutScanningOrGuessingByIp() {
        Printer first = printer(1, "192.168.10.1", "RRF");
        first.setMacAddress(null);
        Printer second = printer(2, "192.168.10.2", "KLIPPER");
        second.setMacAddress("00:00:00:00:00:00");
        when(printers.list()).thenReturn(List.of(first, second));

        service.startManual();
        PrinterRediscoveryResultVO finished = awaitFinished();

        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.updatedCount()).isZero();
        assertThat(finished.unresolvedCount()).isEqualTo(2);
        assertThat(finished.items()).extracting(item -> item.status()).containsExactly("MISSING_MAC", "MISSING_MAC");
        verifyNoInteractions(discovery);
        verify(updater).apply(List.of());
    }

    @Test
    void duplicateIdentitiesProtocolMismatchAndMissingDevicesRemainUnresolved() {
        Printer duplicateKnown = printer(6, "192.168.10.6", "KLIPPER");
        duplicateKnown.setMacAddress(mac(1).toLowerCase().replace(':', '-'));
        duplicateKnown.setMonitorEnabled(false);
        when(printers.list()).thenReturn(List.of(printer(1, "192.168.10.1", "KLIPPER"),
                printer(2, "192.168.10.2", "KLIPPER"), printer(3, "192.168.10.3", "KLIPPER"),
                printer(4, "192.168.10.4", "RRF"), printer(5, "192.168.10.5", "UNSUPPORTED"), duplicateKnown));
        when(discovery.discover(anyList(), anyInt(), any())).thenReturn(List.of(
                identity(1, "192.168.10.21", PrinterProtocolType.KLIPPER),
                identity(2, "192.168.10.22", PrinterProtocolType.KLIPPER),
                identity(2, "192.168.10.23", PrinterProtocolType.KLIPPER),
                identity(3, "192.168.10.24", PrinterProtocolType.RRF)));

        service.startManual();
        PrinterRediscoveryResultVO finished = awaitFinished();

        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.updatedCount()).isZero();
        assertThat(finished.unresolvedCount()).isEqualTo(5);
        assertThat(finished.items()).extracting(item -> item.status()).containsExactly(
                "CONFLICT", "CONFLICT", "CONFLICT", "NOT_FOUND", "UNSUPPORTED_PROTOCOL");
        verify(updater).apply(List.of());
    }

    @Test
    void configuredPrivateSubnetCanRecoverADeviceAcrossNetworks() {
        properties.setSubnets(List.of(" 10.30.40 ", "10.30.40"));
        when(printers.list()).thenReturn(List.of(printer(1, "192.168.10.1", "RRF")));
        when(discovery.discover(anyList(), anyInt(), any())).thenReturn(
                List.of(identity(1, "10.30.40.21", PrinterProtocolType.RRF)));

        service.startManual();
        PrinterRediscoveryResultVO finished = awaitFinished();

        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.subnets()).containsExactly("10.30.40");
        verify(discovery).discover(List.of("10.30.40"), properties.getConcurrency(), properties.getTimeout());
        verify(updater).apply(List.of(new Change(1L, canonicalMac(1), "RRF",
                "192.168.10.1", "10.30.40.21")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8", "127.0.0", "192.168.01", "192.168.1.0/24"})
    void invalidConfiguredRangesFailBeforeScanningOrApplyingResults(String subnet) {
        properties.setSubnets(List.of(subnet));
        when(printers.list()).thenReturn(List.of(printer(1, "192.168.10.1", "RRF")));

        service.startManual();
        PrinterRediscoveryResultVO finished = awaitFinished();

        assertThat(finished.status()).isEqualTo("FAILED");
        assertThat(finished.updatedCount()).isZero();
        verifyNoInteractions(discovery, updater);
    }

    @Test
    void incompleteScanFailureDoesNotApplyPartialDiscoveryEvidence() {
        when(printers.list()).thenReturn(List.of(printer(1, "192.168.10.1", "RRF")));
        when(discovery.discover(anyList(), anyInt(), any())).thenThrow(
                new BusinessException(503, "设备 IP 发现超时，未应用扫描结果"));

        service.startManual();
        PrinterRediscoveryResultVO finished = awaitFinished();

        assertThat(finished.status()).isEqualTo("FAILED");
        assertThat(finished.message()).contains("超时");
        assertThat(finished.updatedCount()).isZero();
        assertThat(finished.items()).isEmpty();
        verifyNoInteractions(updater);
    }

    @Test
    void scanResultsOutsideTheAllowedPrivateRangesAreRejected() {
        when(printers.list()).thenReturn(List.of(printer(1, "192.168.10.1", "RRF")));
        when(discovery.discover(anyList(), anyInt(), any())).thenReturn(
                List.of(identity(1, "10.30.40.21", PrinterProtocolType.RRF)));

        service.startManual();
        PrinterRediscoveryResultVO finished = awaitFinished();

        assertThat(finished.status()).isEqualTo("FAILED");
        assertThat(finished.message()).contains("超出");
        assertThat(finished.updatedCount()).isZero();
        verifyNoInteractions(updater);
    }

    @Test
    void atomicAddressConflictMarksEveryProposedUpdateUncommitted() {
        when(printers.list()).thenReturn(List.of(printer(1, "192.168.10.1", "RRF"),
                printer(2, "192.168.10.2", "RRF")));
        when(discovery.discover(anyList(), anyInt(), any())).thenReturn(List.of(
                identity(1, "192.168.10.21", PrinterProtocolType.RRF),
                identity(2, "192.168.10.22", PrinterProtocolType.RRF)));
        when(updater.apply(anyList())).thenThrow(new BusinessException(409, "新 IP 被其他设备占用"));

        service.startManual();
        PrinterRediscoveryResultVO finished = awaitFinished();

        assertThat(finished.status()).isEqualTo("FAILED");
        assertThat(finished.updatedCount()).isZero();
        assertThat(finished.unresolvedCount()).isEqualTo(2);
        assertThat(finished.items()).extracting(item -> item.status()).containsExactly("CONFLICT", "CONFLICT");
        assertThat(finished.items()).allSatisfy(item -> assertThat(item.message()).contains("未提交"));
    }

    @Test
    void duplicateManualAndAutomaticTriggersReturnTheSameRunningScan() throws Exception {
        properties.setEnabled(true);
        monitorProperties.setEnabled(true);
        when(printers.list()).thenReturn(List.of(printer(1, "192.168.10.1", "RRF")));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(discovery.discover(anyList(), anyInt(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("isolated discovery latch timed out");
            }
            return List.of(identity(1, "192.168.10.21", PrinterProtocolType.RRF));
        });

        PrinterRediscoveryResultVO first = service.startManual();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.startManual().scanId()).isEqualTo(first.scanId());
            service.startAutomatic();
            assertThat(service.getLatest().scanId()).isEqualTo(first.scanId());
        } finally {
            release.countDown();
        }
        assertThat(awaitFinished().scanId()).isEqualTo(first.scanId());
        verify(discovery, times(1)).discover(anyList(), anyInt(), any());
        verify(updater, times(1)).apply(anyList());
    }

    @Test
    void automaticDiscoveryRequiresBothSwitchesAndLimitsRestartCadence() {
        service.startAutomatic();
        properties.setEnabled(true);
        service.startAutomatic();
        monitorProperties.setEnabled(true);
        properties.setInterval(Duration.ofSeconds(29));
        service.startAutomatic();
        properties.setInterval(null);
        service.startAutomatic();
        verifyNoInteractions(printers, discovery, updater);

        properties.setInterval(Duration.ofSeconds(30));
        when(printers.list()).thenReturn(List.of(printer(1, "192.168.10.1", "RRF")));
        service.startAutomatic();
        String scanId = awaitFinished().scanId();
        service.startAutomatic();

        assertThat(service.getLatest().scanId()).isEqualTo(scanId);
        verify(printers, times(1)).list();
        verify(discovery, times(1)).discover(anyList(), anyInt(), any());
    }

    @ParameterizedTest
    @MethodSource("subnetCases")
    void privateNetworkInferenceAcceptsOnlyCanonicalUsablePrivateAddresses(String address, String prefix) {
        assertThat(PrinterRediscoveryService.privateSubnet(address)).isEqualTo(prefix);
    }

    @Test
    void shutdownPreventsAnyFurtherBackgroundOrManualScan() {
        service.destroy();
        properties.setEnabled(true);
        monitorProperties.setEnabled(true);

        service.startAutomatic();
        assertThatThrownBy(service::startManual).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(503));

        assertThat(service.getLatest().status()).isEqualTo("IDLE");
        verifyNoInteractions(printers, discovery, updater);
    }

    private PrinterRediscoveryResultVO awaitFinished() {
        await().pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(20))
                .atMost(Duration.ofSeconds(3)).until(() -> !"RUNNING".equals(service.getLatest().status()));
        return service.getLatest();
    }

    private static void authenticate(String role) {
        SecurityContextHolder.clearContext();
        if (role != null) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    1L, "fixture-admin", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        }
    }

    private static Printer printer(long id, String address, String protocol) {
        Printer printer = new Printer();
        printer.setId(id);
        printer.setName("Fixture printer " + id);
        printer.setMacAddress(mac(id));
        printer.setIpAddress(address);
        printer.setFirmwareType(protocol);
        printer.setMonitorEnabled(true);
        printer.setApiKey("must-never-appear-in-rediscovery-result");
        return printer;
    }

    private static Identity identity(long id, String address, PrinterProtocolType protocol) {
        return new Identity(address, mac(id), protocol);
    }

    private static String mac(long id) {
        return "AA:BB:CC:DD:EE:%02X".formatted(id);
    }

    private String canonicalMac(long id) {
        return macUtil.normalizeMacAddress(mac(id));
    }

    private static Stream<Arguments> subnetCases() {
        return Stream.of(Arguments.of("10.0.0.1", "10.0.0"),
                Arguments.of("172.16.0.1", "172.16.0"), Arguments.of("172.31.255.254", "172.31.255"),
                Arguments.of("192.168.10.1", "192.168.10"), Arguments.of("172.15.0.1", null),
                Arguments.of("172.32.0.1", null), Arguments.of("8.8.8.8", null),
                Arguments.of("192.168.10.0", null), Arguments.of("192.168.10.255", null),
                Arguments.of("192.168.10.256", null), Arguments.of("192.168.010.1", null),
                Arguments.of("192.168.10.1 ", null), Arguments.of("printer.local", null),
                Arguments.of("127.0.0.1", null), Arguments.of(null, null));
    }
}
