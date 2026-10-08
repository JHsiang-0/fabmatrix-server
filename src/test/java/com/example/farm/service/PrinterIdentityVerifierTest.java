package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.config.PrinterRediscoveryProperties;
import com.example.farm.entity.Printer;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterNetworkDiscovery;
import com.example.farm.protocol.PrinterNetworkDiscovery.Identity;
import com.example.farm.protocol.PrinterProtocolType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Identity observations are fixtures; no protocol calls or persisted devices are used. */
class PrinterIdentityVerifierTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final String ADDRESS = "192.168.10.1";

    private PrinterNetworkDiscovery discovery;
    private PrinterRediscoveryProperties properties;
    private PrinterIdentityVerifier verifier;
    private Printer printer;

    @BeforeEach
    void setUp() {
        discovery = mock(PrinterNetworkDiscovery.class);
        properties = new PrinterRediscoveryProperties();
        properties.setEnabled(true);
        verifier = new PrinterIdentityVerifier(discovery, new MacAddressUtil(), properties);
        printer = new Printer();
        printer.setId(703L);
        printer.setIpAddress(ADDRESS);
        printer.setMacAddress(MAC);
        printer.setFirmwareType("RRF");
        printer.setApiKey("registered-device-secret-must-not-follow-reused-ip");
    }

    @Test
    void disabledFeatureNullPrinterAndMissingIdDoNotContactDevices() {
        properties.setEnabled(false);
        verifier.verify(printer);
        verifier.verifyFresh(printer);
        properties.setEnabled(true);
        verifier.verify(null);
        verifier.verifyFresh(null);
        printer.setId(null);
        verifier.verify(printer);
        verifier.verifyFresh(printer);

        verifyNoInteractions(discovery);
    }

    @Test
    void legacyMissingOrInvalidMacKeepsItsExistingMonitoringBehavior() {
        printer.setFirmwareType("legacy-unknown");
        for (String mac : new String[] {null, "", "unavailable", "00:00:00:00:00:00"}) {
            printer.setMacAddress(mac);
            verifier.verify(printer);
            verifier.verifyFresh(printer);
        }

        verifyNoInteractions(discovery);
    }

    @Test
    void matchingIdentityUsesCachedProofAndNeverSendsTheRegisteredApiKey() {
        when(discovery.identify(any())).thenReturn(matchingIdentity());

        verifier.verify(printer);
        printer.setMacAddress("aa-bb-cc-dd-ee-01");
        printer.setFirmwareType("rrf");
        verifier.verify(printer);

        ArgumentCaptor<PrinterEndpoint> endpoint = ArgumentCaptor.forClass(PrinterEndpoint.class);
        verify(discovery, times(1)).identify(endpoint.capture());
        assertThat(endpoint.getValue().printerId()).isEqualTo(703L);
        assertThat(endpoint.getValue().ipAddress()).isEqualTo(ADDRESS);
        assertThat(endpoint.getValue().protocolType()).isEqualTo(PrinterProtocolType.RRF);
        assertThat(endpoint.getValue().apiKey()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("mismatchedIdentities")
    void mismatchedMacProtocolAddressOrMissingProofIsRejectedAndNegativelyCached(Identity identity) {
        when(discovery.identify(any())).thenReturn(identity);

        assertIdentityFailure();
        assertIdentityFailure();

        verify(discovery, times(1)).identify(any());
    }

    @Test
    void forcedVerificationBypassesAnExistingSuccessfulProof() {
        when(discovery.identify(any())).thenReturn(matchingIdentity(),
                new Identity(ADDRESS, "AA:BB:CC:DD:EE:02", PrinterProtocolType.RRF));
        verifier.verify(printer);

        assertThatThrownBy(() -> verifier.verifyFresh(printer)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(503));
        assertIdentityFailure();

        verify(discovery, times(2)).identify(any());
    }

    @Test
    void forcedVerificationCanReplaceAFailedProofImmediatelyAfterRecovery() {
        when(discovery.identify(any())).thenReturn(null, matchingIdentity());
        assertIdentityFailure();

        verifier.verifyFresh(printer);
        verifier.verify(printer);

        verify(discovery, times(2)).identify(any());
    }

    @Test
    void invalidationOnlyRemovesTheRequestedDeviceProof() {
        when(discovery.identify(any())).thenReturn(matchingIdentity());
        verifier.verify(printer);
        verifier.invalidate(null);
        verifier.invalidate(999L);
        verifier.verify(printer);
        verify(discovery, times(1)).identify(any());

        verifier.invalidate(703L);
        verifier.verify(printer);

        verify(discovery, times(2)).identify(any());
    }

    @Test
    void changingIpAddressDoesNotReuseTheOldAddressProof() {
        when(discovery.identify(any())).thenReturn(matchingIdentity(),
                new Identity("192.168.10.21", MAC, PrinterProtocolType.RRF));
        verifier.verify(printer);
        printer.setIpAddress("192.168.10.21");

        verifier.verify(printer);

        ArgumentCaptor<PrinterEndpoint> endpoints = ArgumentCaptor.forClass(PrinterEndpoint.class);
        verify(discovery, times(2)).identify(endpoints.capture());
        assertThat(endpoints.getAllValues()).extracting(PrinterEndpoint::ipAddress)
                .containsExactly(ADDRESS, "192.168.10.21");
        assertThat(endpoints.getAllValues()).allSatisfy(endpoint -> assertThat(endpoint.apiKey()).isEmpty());
    }

    @Test
    void changingMacOrProtocolRequiresFreshIdentityProof() {
        when(discovery.identify(any())).thenReturn(matchingIdentity(),
                new Identity(ADDRESS, "AA:BB:CC:DD:EE:02", PrinterProtocolType.RRF),
                new Identity(ADDRESS, "AA:BB:CC:DD:EE:02", PrinterProtocolType.KLIPPER));
        verifier.verify(printer);
        printer.setMacAddress("AA:BB:CC:DD:EE:02");
        verifier.verify(printer);
        printer.setFirmwareType("KLIPPER");

        verifier.verify(printer);

        verify(discovery, times(3)).identify(any());
    }

    @Test
    void communicationFailureRemovesSuccessfulProofBeforeTheNextQuery() {
        when(discovery.identify(any())).thenReturn(matchingIdentity(),
                new Identity(ADDRESS, "AA:BB:CC:DD:EE:02", PrinterProtocolType.RRF));
        verifier.verify(printer);
        verifier.onCommunicationFailure(null);
        verifier.onCommunicationFailure(999L);
        verifier.verify(printer);
        verify(discovery, times(1)).identify(any());

        verifier.onCommunicationFailure(703L);
        assertIdentityFailure();

        verify(discovery, times(2)).identify(any());
    }

    @Test
    void communicationFailureKeepsFailedProofToLimitRepeatedIdentityRequests() {
        when(discovery.identify(any())).thenReturn(null);
        assertIdentityFailure();

        verifier.onCommunicationFailure(703L);
        assertIdentityFailure();

        verify(discovery, times(1)).identify(any());
    }

    private void assertIdentityFailure() {
        assertThatThrownBy(() -> verifier.verify(printer)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(503));
    }

    private static Identity matchingIdentity() {
        return new Identity(ADDRESS, MAC, PrinterProtocolType.RRF);
    }

    private static Stream<Arguments> mismatchedIdentities() {
        return Stream.of(Arguments.of((Object) null),
                Arguments.of(new Identity(ADDRESS, "AA:BB:CC:DD:EE:02", PrinterProtocolType.RRF)),
                Arguments.of(new Identity(ADDRESS, MAC, PrinterProtocolType.KLIPPER)),
                Arguments.of(new Identity("192.168.10.21", MAC, PrinterProtocolType.RRF)),
                Arguments.of(new Identity(ADDRESS, "unavailable", PrinterProtocolType.RRF)),
                Arguments.of(new Identity(ADDRESS, MAC, null)),
                Arguments.of(new Identity(null, MAC, PrinterProtocolType.RRF)));
    }
}
