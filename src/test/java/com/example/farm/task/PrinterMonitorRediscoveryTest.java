package com.example.farm.task;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.config.PrinterMonitorProperties;
import com.example.farm.config.PrinterRediscoveryProperties;
import com.example.farm.entity.Printer;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterNetworkDiscovery;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterIdentityVerifier;
import com.example.farm.service.PrinterRediscoveryService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.WebSocketEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises the monitor's locked fetch directly; no scheduler, network or persistence is started. */
class PrinterMonitorRediscoveryTest {

    private PrinterService printers;
    private PrinterCacheService cache;
    private PrinterProtocolAdapterFactory adapters;
    private PrinterProtocolAdapter adapter;
    private PrintJobService jobs;
    private WebSocketEventPublisher events;
    private PrinterIdentityVerifier verifier;
    private PrinterRediscoveryService rediscovery;
    private PrinterMonitorProperties properties;
    private PrinterMonitorTask task;

    @BeforeEach
    void setUp() {
        printers = mock(PrinterService.class);
        cache = mock(PrinterCacheService.class);
        adapters = mock(PrinterProtocolAdapterFactory.class);
        adapter = mock(PrinterProtocolAdapter.class);
        jobs = mock(PrintJobService.class);
        events = mock(WebSocketEventPublisher.class);
        verifier = mock(PrinterIdentityVerifier.class);
        rediscovery = mock(PrinterRediscoveryService.class);
        properties = new PrinterMonitorProperties();
        properties.setConcurrency(1);
        properties.setFailureThreshold(3);
        task = new PrinterMonitorTask(printers, cache, adapters, jobs, events, properties);
        ReflectionTestUtils.setField(task, "identityVerifier", verifier);
        ReflectionTestUtils.setField(task, "rediscoveryService", rediscovery);
        when(cache.updatePrinterHealthWithLock(any())).thenReturn(true);
    }

    @AfterEach
    void stopExecutor() {
        task.destroy();
    }

    @Test
    void reReadsTheLatestAddressUnderThePrinterLockAndVerifiesBeforeQueryingStatus() {
        Printer queuedSnapshot = printer("192.168.10.1");
        Printer current = printer("192.168.10.21");
        when(printers.getById(703L)).thenAnswer(invocation -> {
            assertThat(PrinterLock.isHeld(703L)).isTrue();
            return current;
        });
        when(adapters.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenReturn(idleStatus());

        Object result = fetch(queuedSnapshot);

        assertThat(ReflectionTestUtils.getField(result, "online")).isEqualTo(true);
        var order = inOrder(printers, verifier, adapters, adapter);
        order.verify(printers).getById(703L);
        order.verify(verifier).verifyFresh(current);
        order.verify(adapters).getAdapter("RRF");
        ArgumentCaptor<PrinterEndpoint> endpoint = ArgumentCaptor.forClass(PrinterEndpoint.class);
        order.verify(adapter).getStatus(endpoint.capture());
        assertThat(endpoint.getValue().ipAddress()).isEqualTo("192.168.10.21");
        assertThat(queuedSnapshot.getIpAddress()).isEqualTo("192.168.10.1");
        verifyNoInteractions(rediscovery);
    }

    @Test
    void disabledOrDeletedCurrentRecordsAreSkippedBeforeAnyDeviceInteraction() {
        Printer queuedSnapshot = printer("192.168.10.1");
        Printer current = printer("192.168.10.21");
        current.setMonitorEnabled(false);
        when(printers.getById(703L)).thenReturn(current, null);

        Object disabled = fetch(queuedSnapshot);
        Object deleted = fetch(queuedSnapshot);

        assertThat(ReflectionTestUtils.getField(disabled, "skipped")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(deleted, "online")).isEqualTo(false);
        verifyNoInteractions(verifier, adapters, adapter, cache, rediscovery);
    }

    @Test
    void failedIdentityProofPreventsStatusQueriesAndPreservesFarmJobBindings() {
        properties.setFailureThreshold(1);
        Printer current = printer("192.168.10.1");
        current.setStatus("PRINTING");
        current.setCurrentJobId(1001L);
        current.setIsSafeToPrint(true);
        current.setLastKnownStatus("PRINTING");
        when(printers.getById(703L)).thenReturn(current);
        doThrow(new BusinessException(503, "设备身份无法确认")).when(verifier).verifyFresh(current);

        Object result = fetch(current);

        assertThat(ReflectionTestUtils.getField(result, "online")).isEqualTo(false);
        assertThat(current.getCurrentJobId()).isEqualTo(1001L);
        assertThat(current.getIsSafeToPrint()).isTrue();
        assertThat(current.getStatus()).isEqualTo("PRINTING");
        assertThat(current.getLastKnownStatus()).isEqualTo("PRINTING");
        verify(verifier).onCommunicationFailure(703L);
        verify(rediscovery).startAutomatic();
        verify(cache).markPrinterOffline(703L);
        verifyNoInteractions(adapters, adapter, jobs);
    }

    @Test
    void consecutiveIdentityFailuresRequestAddressRecoveryOnlyAfterTheThreshold() {
        Printer current = printer("192.168.10.1");
        when(printers.getById(703L)).thenReturn(current);
        doThrow(new BusinessException(503, "设备身份无法确认")).when(verifier).verifyFresh(current);

        fetch(current);
        fetch(current);
        verify(rediscovery, never()).startAutomatic();
        fetch(current);

        verify(rediscovery, times(1)).startAutomatic();
        verify(verifier, times(3)).onCommunicationFailure(703L);
        ArgumentCaptor<Printer> healthWrites = ArgumentCaptor.forClass(Printer.class);
        verify(cache, times(3)).updatePrinterHealthWithLock(healthWrites.capture());
        assertThat(healthWrites.getAllValues()).extracting(Printer::getConsecutiveFailureCount)
                .containsExactly(1, 2, 3);
        assertThat(healthWrites.getAllValues()).extracting(Printer::getConnectionStatus)
                .containsExactly("ONLINE", "ONLINE", "OFFLINE");
        verify(cache, times(1)).clearStatusCache(703L);
        verifyNoInteractions(adapters, adapter, jobs);
    }

    @Test
    void recoverySchedulingFailureDoesNotEscapeOrLoseTheOfflineMonitorResult() {
        Printer current = printer("192.168.10.1");
        current.setConsecutiveFailureCount(2);
        when(printers.getById(703L)).thenReturn(current);
        when(adapters.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenThrow(new IllegalStateException("device timed out"));
        doThrow(new IllegalStateException("isolated rediscovery scheduler failure"))
                .when(rediscovery).startAutomatic();

        Object result = fetch(current);

        assertThat(ReflectionTestUtils.getField(result, "online")).isEqualTo(false);
        assertThat(ReflectionTestUtils.getField(result, "connectionStatus")).isEqualTo("OFFLINE");
        assertThat(current.getConsecutiveFailureCount()).isEqualTo(3);
        verify(verifier).onCommunicationFailure(703L);
        verify(cache).markPrinterOffline(703L);
        verify(rediscovery).startAutomatic();
    }

    @Test
    void emptyStatusResponseAlsoInvalidatesProofAndRequestsRecoveryAtTheThreshold() {
        Printer current = printer("192.168.10.1");
        current.setConsecutiveFailureCount(2);
        when(printers.getById(703L)).thenReturn(current);
        when(adapters.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenReturn(null);

        Object result = fetch(current);

        assertThat(ReflectionTestUtils.getField(result, "online")).isEqualTo(false);
        assertThat(current.getConsecutiveFailureCount()).isEqualTo(3);
        verify(verifier).onCommunicationFailure(703L);
        verify(rediscovery).startAutomatic();
        verify(cache).markPrinterOffline(703L);
    }

    @Test
    void realIdentityVerifierKeepsMissingMacDevicesCompatible() {
        PrinterNetworkDiscovery discovery = installRealVerifier();
        Printer current = printer("192.168.10.1");
        current.setMacAddress(null);
        when(printers.getById(703L)).thenReturn(current);
        when(adapters.getAdapter("RRF")).thenReturn(adapter);
        when(adapter.getStatus(any())).thenReturn(idleStatus());

        Object result = fetch(current);

        assertThat(ReflectionTestUtils.getField(result, "online")).isEqualTo(true);
        verify(adapter).getStatus(any());
        verifyNoInteractions(discovery, rediscovery);
    }

    @Test
    void everyMonitorPollRechecksIdentityEvenWhenThereIsARecentSuccessfulProof() {
        PrinterNetworkDiscovery discovery = installRealVerifier();
        Printer current = printer("192.168.10.1");
        when(discovery.identify(any())).thenReturn(
                new PrinterNetworkDiscovery.Identity(current.getIpAddress(), current.getMacAddress(), PrinterProtocolType.RRF),
                new PrinterNetworkDiscovery.Identity(current.getIpAddress(), "AA:BB:CC:DD:EE:02", PrinterProtocolType.RRF));
        PrinterIdentityVerifier realVerifier = (PrinterIdentityVerifier)
                ReflectionTestUtils.getField(task, "identityVerifier");
        realVerifier.verify(current);
        when(printers.getById(703L)).thenReturn(current);

        Object result = fetch(current);

        assertThat(ReflectionTestUtils.getField(result, "online")).isEqualTo(false);
        verify(discovery, times(2)).identify(any());
        ArgumentCaptor<PrinterEndpoint> endpoints = ArgumentCaptor.forClass(PrinterEndpoint.class);
        verify(discovery, times(2)).identify(endpoints.capture());
        assertThat(endpoints.getAllValues()).allSatisfy(endpoint -> assertThat(endpoint.apiKey()).isEmpty());
        verifyNoInteractions(adapters, adapter, jobs);
    }

    private Object fetch(Printer queuedSnapshot) {
        return ReflectionTestUtils.invokeMethod(task, "fetchAndUpdateStatus", queuedSnapshot);
    }

    private PrinterNetworkDiscovery installRealVerifier() {
        PrinterNetworkDiscovery discovery = mock(PrinterNetworkDiscovery.class);
        PrinterRediscoveryProperties rediscoveryProperties = new PrinterRediscoveryProperties();
        rediscoveryProperties.setEnabled(true);
        ReflectionTestUtils.setField(task, "identityVerifier",
                new PrinterIdentityVerifier(discovery, new MacAddressUtil(), rediscoveryProperties));
        return discovery;
    }

    private static Printer printer(String address) {
        Printer printer = new Printer();
        printer.setId(703L);
        printer.setName("Fixture printer");
        printer.setIpAddress(address);
        printer.setMacAddress("AA:BB:CC:DD:EE:01");
        printer.setFirmwareType("RRF");
        printer.setApiKey("fixture-device-secret");
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("ONLINE");
        printer.setConsecutiveFailureCount(0);
        printer.setStatus("IDLE");
        return printer;
    }

    private static PrinterDeviceStatus idleStatus() {
        return new PrinterDeviceStatus(PrinterStatus.IDLE, "ready", null, null, BigDecimal.ZERO,
                null, null, null, null, null, null, null);
    }
}
