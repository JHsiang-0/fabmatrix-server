package com.example.farm.protocol;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PrinterNetworkDiscoveryTest {

    private PrinterProtocolDetector detector;
    private RrfApiClient rrf;
    private MacAddressUtil mac;
    private PrinterNetworkDiscovery discovery;

    @BeforeEach
    void setUp() {
        detector = mock(PrinterProtocolDetector.class);
        rrf = mock(RrfApiClient.class);
        mac = spy(new MacAddressUtil());
        // 每个用例都隔离真实 HTTP、ARP 和系统进程。
        doReturn(null).when(mac).getMacFromArpTable(anyString());
        doReturn(null).when(mac).getMacFromMoonrakerApi(anyString(), any());
        discovery = new PrinterNetworkDiscovery(detector, rrf, mac);
    }

    @Test
    void identifyDoesNotSendKnownRrfEndpointsSecretBeforeCheckingIdentity() {
        var endpoint = new PrinterEndpoint(1L, "192.168.1.80", "device-key", PrinterProtocolType.RRF);
        var anonymousEndpoint = new PrinterEndpoint(1L, "192.168.1.80", "", PrinterProtocolType.RRF);
        when(rrf.getNetworkIdentity(anonymousEndpoint)).thenReturn(
                new RrfApiClient.RrfNetworkIdentity("192.168.1.80", "5C-8E-8B-10-CB-FD"));

        assertThat(discovery.identify(endpoint)).isEqualTo(
                new PrinterNetworkDiscovery.Identity("192.168.1.80", "5c:8e:8b:10:cb:fd", PrinterProtocolType.RRF));
        verify(rrf).getNetworkIdentity(anonymousEndpoint);
        verify(rrf, never()).getNetworkIdentity(endpoint);
        verify(mac, never()).getMacFromArpTable(anyString());
        verifyNoMoreInteractions(rrf);
    }

    @Test
    void identifyDoesNotSendKnownMoonrakerEndpointsSecretBeforeCheckingIdentity() {
        var endpoint = new PrinterEndpoint(1L, "192.168.1.80", "device-key", PrinterProtocolType.KLIPPER);
        doReturn("5c:8e:8b:10:cb:fd").when(mac).getMacFromMoonrakerApi("192.168.1.80", "");

        assertThat(discovery.identify(endpoint).macAddress()).isEqualTo("5c:8e:8b:10:cb:fd");
        verify(mac).getMacFromMoonrakerApi("192.168.1.80", "");
        verify(mac, never()).getMacFromMoonrakerApi("192.168.1.80", "device-key");
        verifyNoInteractions(rrf);
    }

    @Test
    void identifyRejectsRrfNetworkIdentityFromOtherIp() {
        var endpoint = new PrinterEndpoint(1L, "192.168.1.80", "device-key", PrinterProtocolType.RRF);
        when(rrf.getNetworkIdentity(new PrinterEndpoint(1L, "192.168.1.80", "", PrinterProtocolType.RRF))).thenReturn(
                new RrfApiClient.RrfNetworkIdentity("192.168.1.180", "5c:8e:8b:10:cb:fd"));

        assertThat(discovery.identify(endpoint)).isNull();
        verify(mac).getMacFromArpTable("192.168.1.80");
    }

    @Test
    void identifyFallsBackToValidatedTargetArpAfterApiFailure() {
        var endpoint = new PrinterEndpoint(1L, "192.168.1.80", "device-key", PrinterProtocolType.RRF);
        when(rrf.getNetworkIdentity(new PrinterEndpoint(1L, "192.168.1.80", "", PrinterProtocolType.RRF)))
                .thenThrow(new IllegalStateException("unavailable"));
        doReturn("5C-8E-8B-10-CB-FD").when(mac).getMacFromArpTable("192.168.1.80");

        assertThat(discovery.identify(endpoint)).isEqualTo(
                new PrinterNetworkDiscovery.Identity("192.168.1.80", "5c:8e:8b:10:cb:fd", PrinterProtocolType.RRF));
    }

    @Test
    void identifyRejectsInvalidMacAndInvalidEndpoint() {
        var endpoint = new PrinterEndpoint(1L, "192.168.1.80", "device-key", PrinterProtocolType.KLIPPER);
        doReturn("ff:ff:ff:ff:ff:ff").when(mac).getMacFromMoonrakerApi("192.168.1.80", "");
        doReturn("00:00:00:00:00:00").when(mac).getMacFromArpTable("192.168.1.80");

        assertThat(discovery.identify(endpoint)).isNull();
        assertThat(discovery.identify(null)).isNull();
        assertThat(discovery.identify(new PrinterEndpoint(1L, "localhost", null, PrinterProtocolType.RRF))).isNull();
        assertThat(discovery.identify(new PrinterEndpoint(1L, "192.168.1.80", null, null))).isNull();
    }

    @Test
    void discoverScansEveryHostOnceWithEmptyKeysAndReturnsOnlyIdentifiedPrinters() {
        when(detector.detect("192.168.1.80")).thenReturn(PrinterProtocolType.RRF);
        when(detector.detect("192.168.1.9")).thenReturn(PrinterProtocolType.KLIPPER);
        when(rrf.getNetworkIdentity(any())).thenAnswer(invocation -> {
            PrinterEndpoint endpoint = invocation.getArgument(0);
            assertThat(endpoint.printerId()).isNull();
            assertThat(endpoint.apiKey()).isEmpty();
            return new RrfApiClient.RrfNetworkIdentity(endpoint.ipAddress(), "5c:8e:8b:10:cb:fd");
        });
        doReturn("02:00:00:00:00:09").when(mac).getMacFromMoonrakerApi("192.168.1.9", "");

        assertThat(discovery.discover(List.of("192.168.1", "192.168.1"), 8, Duration.ofSeconds(5)))
                .containsExactly(
                        new PrinterNetworkDiscovery.Identity("192.168.1.9", "02:00:00:00:00:09", PrinterProtocolType.KLIPPER),
                        new PrinterNetworkDiscovery.Identity("192.168.1.80", "5c:8e:8b:10:cb:fd", PrinterProtocolType.RRF));
        verify(detector, times(254)).detect(anyString());
        verify(detector, never()).detect("192.168.1.0");
        verify(detector, never()).detect("192.168.1.255");
        verify(rrf).getNetworkIdentity(new PrinterEndpoint(null, "192.168.1.80", "", PrinterProtocolType.RRF));
        verifyNoMoreInteractions(rrf);
    }

    @Test
    void unknownProtocolCannotBecomeArpOnlyCandidate() {
        assertThat(discovery.discover(List.of("192.168.1"), 4, Duration.ofSeconds(5))).isEmpty();
        verify(mac, never()).getMacFromArpTable(anyString());
        verifyNoInteractions(rrf);
    }

    @Test
    void discoveryConcurrencyAndWorkerThreadsAreBounded() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicBoolean daemon = new AtomicBoolean(true);
        when(detector.detect(anyString())).thenAnswer(invocation -> {
            int running = active.incrementAndGet();
            maxActive.accumulateAndGet(running, Math::max);
            daemon.compareAndSet(true, Thread.currentThread().isDaemon());
            try {
                Thread.sleep(1);
                return null;
            } finally {
                active.decrementAndGet();
            }
        });

        assertThat(discovery.discover(List.of("192.168.1"), 4, Duration.ofSeconds(5))).isEmpty();
        assertThat(maxActive.get()).isBetween(1, 4);
        assertThat(daemon.get()).isTrue();
    }

    @Test
    void deadlineFailsEntireScanAndInterruptsWorkersInsteadOfReturningPartialResults() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(detector.detect(anyString())).thenAnswer(invocation -> {
            if ("192.168.1.1".equals(invocation.getArgument(0))) {
                return PrinterProtocolType.KLIPPER;
            }
            try {
                release.await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        });
        doReturn("02:00:00:00:00:01").when(mac).getMacFromMoonrakerApi("192.168.1.1", "");
        try {
            assertThatThrownBy(() -> discovery.discover(List.of("192.168.1"), 2, Duration.ofMillis(200)))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo(503))
                    .hasMessageContaining("超时");
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void callerInterruptionRemainsSetAndDoesNotStartNetworkTasks() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> discovery.discover(List.of("192.168.1"), 2, Duration.ofSeconds(1)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(detector, rrf);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidScanConfigurationFailsBeforeAnyNetworkCall() {
        for (String prefix : List.of("192.168.1.1", "192.168.256", "192.168.01", "localhost", "127.0.0", "224.0.0")) {
            assertThatThrownBy(() -> discovery.discover(List.of(prefix), 2, Duration.ofSeconds(1)))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> discovery.discover(List.of("192.168.1"), 65, Duration.ofSeconds(1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> discovery.discover(List.of("192.168.1"), 2, Duration.ZERO))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(detector, rrf);
    }
}
