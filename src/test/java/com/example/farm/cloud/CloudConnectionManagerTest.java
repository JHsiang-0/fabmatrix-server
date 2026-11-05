package com.example.farm.cloud;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.dto.CloudBindRequest;
import com.example.farm.entity.dto.CloudReportingRequest;
import com.example.farm.service.AuditLogService;
import com.example.farm.service.PrinterEventOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CloudConnectionManagerTest {
    @TempDir Path temp;
    private final CloudGatewayProperties properties = new CloudGatewayProperties();
    private final CloudSnapshotSource source = mock(CloudSnapshotSource.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final CloudConnectionClient remote = mock(CloudConnectionClient.class);
    private final CloudGatewayAgent agent = mock(CloudGatewayAgent.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PrinterEventOutbox> outboxes = mock(ObjectProvider.class);
    private final ObjectMapper json = new ObjectMapper();
    private CloudConnectionManager manager;
    private CloudWire.Credential credential;

    @BeforeEach void setUp() throws Exception {
        properties.setCredentialFile(temp.resolve("config/cloud-gateway.json").toString());
        properties.setStateDirectory(temp.resolve("state").toString());
        authenticate("ADMIN");
        String gatewayId = UUID.randomUUID().toString();
        credential = new CloudWire.Credential(gatewayId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, "fmc_g_" + "g".repeat(43), System.currentTimeMillis() + 86400000,
                gatewayId + ".v1", gatewayId, "fabmatrix/v1/gateways/" + gatewayId + "/bindings/1/");
        when(remote.bind(any(), any())).thenReturn(credential);
        when(remote.profile(any(), any())).thenReturn(new CloudConnectionSettings.BindingInfo(gatewayId, "测试网关",
                credential.organizationId(), "测试组织", credential.farmId(), "测试农场"));
        when(source.printerCount()).thenReturn(3L);
        when(agent.status()).thenReturn(new CloudGatewayAgent.Status("CONNECTED", true, gatewayId, 1234L, null, false));
        manager = newManager();
        manager.start();
    }

    @AfterEach void cleanUp() { manager.stop(); SecurityContextHolder.clearContext(); }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                1L, "test-admin", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private CloudConnectionManager newManager() {
        var result = spy(new CloudConnectionManager(properties, source, outboxes, audit, remote));
        doReturn(agent).when(result).createAgent(any(), any());
        return result;
    }

    private CloudBindRequest request(long revision) {
        return new CloudBindRequest(revision, "https://cloud.example.test", "ssl://mqtt.example.test:8883",
                "fmc_b_" + "b".repeat(43), "", true);
    }

    @Test void pairingSurvivesRestartAndCompletesDisabledWithoutLeakingDeviceCredential() throws Exception {
        var session = new CloudConnectionClient.PairingStart(UUID.randomUUID().toString(), "fmc_pd_" + "d".repeat(43),
                "fmc_pa_" + "a".repeat(43), System.currentTimeMillis() + 600000);
        when(remote.startPairing(any())).thenReturn(session);
        var start = manager.startPairing(new com.example.farm.entity.dto.CloudPairingStartRequest(
                0, "https://cloud.example.test", "ssl://mqtt.example.test:8883", "", true));
        assertThat(start.qrContent()).contains(session.approvalToken()).doesNotContain(session.deviceToken(), "fmc_g_");
        assertThat(start.toString()).doesNotContain(session.approvalToken());
        assertThat(manager.connection().bound()).isFalse();
        manager.stop();
        manager = newManager();
        manager.start();
        var request = new com.example.farm.entity.dto.CloudPairingRequest(0, UUID.fromString(session.pairingId()));
        when(remote.pollPairing(any(), any(), any())).thenAnswer(invocation -> {
            var c = new CloudWire.Credential(credential.gatewayId(), credential.organizationId(), credential.farmId(),
                    1, invocation.getArgument(2), credential.expiresAt(), credential.mqttClientId(), credential.mqttUsername(), credential.topicPrefix());
            return new CloudConnectionClient.PairingResult("COMPLETED", session.expiresAt(), c);
        });
        assertThat(manager.pollPairing(request).state()).isEqualTo("COMPLETED");
        assertThat(manager.pollPairing(request).state()).isEqualTo("COMPLETED");
        assertThat(manager.connection().bound()).isTrue();
        assertThat(manager.connection().enabled()).isFalse();
        verify(agent, never()).start();
        verify(remote, org.mockito.Mockito.times(1)).pollPairing(any(), any(), any());
        assertThatThrownBy(() -> manager.startPairing(new com.example.farm.entity.dto.CloudPairingStartRequest(
                1, "https://cloud.example.test", "ssl://mqtt.example.test:8883", "", true))).isInstanceOf(BusinessException.class);
    }

    @Test void canceledPairingCannotBePolledAndViewerCannotStart() throws Exception {
        var session = new CloudConnectionClient.PairingStart(UUID.randomUUID().toString(), "fmc_pd_" + "d".repeat(43),
                "fmc_pa_" + "a".repeat(43), System.currentTimeMillis() + 600000);
        when(remote.startPairing(any())).thenReturn(session);
        var start = new com.example.farm.entity.dto.CloudPairingStartRequest(0, "https://cloud.example.test", "ssl://mqtt.example.test:8883", "", true);
        authenticate("VIEWER");
        assertThatThrownBy(() -> manager.startPairing(start)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(remote);
        authenticate("ADMIN");
        manager.startPairing(start);
        var request = new com.example.farm.entity.dto.CloudPairingRequest(0, UUID.fromString(session.pairingId()));
        manager.cancelPairing(request);
        assertThat(Files.exists(temp.resolve("config/cloud-pairing.json"))).isFalse();
        assertThatThrownBy(() -> manager.pollPairing(request)).isInstanceOf(BusinessException.class);
        verify(remote, never()).pollPairing(any(), any(), any());
    }

    @Test void defaultIsDisabledAndDoesNotCreateNetworkOrReportingWork() {
        assertThat(manager.connection().bound()).isFalse();
        assertThat(manager.connection().enabled()).isFalse();
        assertThat(Files.exists(temp.resolve("config/cloud-connection.json"))).isFalse();
        verifyNoInteractions(remote, agent);
    }

    @Test void bindIsDurableDisabledAndRedactedAndCanBeEnabledWithoutRestart() throws Exception {
        var bound = manager.bind(request(0));
        assertThat(bound.bound()).isTrue();
        assertThat(bound.enabled()).isFalse();
        assertThat(bound.organizationName()).isEqualTo("测试组织");
        assertThat(bound.printerCount()).isEqualTo(3);
        assertThat(bound.fullModelEnabled()).isTrue();
        assertThat(json.writeValueAsString(bound)).doesNotContain(credential.credential(), request(0).bindingToken());
        assertThat(request(0).toString()).doesNotContain(request(0).bindingToken());
        verify(agent, never()).start();

        manager.reporting(new CloudReportingRequest(bound.revision(), true));
        verify(agent).start();
        var restored = newManager();
        try {
            restored.start();
            assertThat(restored.connection().enabled()).isTrue();
            assertThat(restored.connection().gatewayId()).isEqualTo(credential.gatewayId());
            assertThat(restored.connection().organizationName()).isEqualTo("测试组织");
        } finally { restored.stop(); }
        verify(audit).recordSuccess(eq("CLOUD_BIND"), eq("CLOUD_GATEWAY"), eq(credential.gatewayId()), any());
        verify(audit).recordSuccess(eq("CLOUD_ENABLE"), eq("CLOUD_GATEWAY"), eq(credential.gatewayId()), any());
    }

    @Test void disableWorksOfflineAndRetainsIdentityAcrossRestart() throws Exception {
        var bound = manager.bind(request(0));
        var enabled = manager.reporting(new CloudReportingRequest(bound.revision(), true));
        var disabled = manager.reporting(new CloudReportingRequest(enabled.revision(), false));
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.bound()).isTrue();
        assertThat(disabled.lastAckAt()).isEqualTo(1234L);
        verify(agent).stop();
        verify(remote, never()).disconnect(any(), any());
        var restored = newManager();
        try {
            restored.start();
            assertThat(restored.connection().state()).isEqualTo("DISABLED");
            assertThat(restored.connection().gatewayId()).isEqualTo(credential.gatewayId());
        } finally { restored.stop(); }
    }

    @Test void staleRevisionAndNonAdminCannotMutateOrReadConfiguration() {
        manager.bind(request(0));
        assertThatThrownBy(() -> manager.reporting(new CloudReportingRequest(0, true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("刷新");
        authenticate("OPERATOR");
        assertThatThrownBy(() -> manager.connection()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> manager.bind(request(1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> manager.unbind(1)).isInstanceOf(BusinessException.class);
    }

    @Test void unbindFailureStopsReportingAndRetainsCredentialForRetry() throws Exception {
        var bound = manager.bind(request(0));
        var enabled = manager.reporting(new CloudReportingRequest(bound.revision(), true));
        doThrow(new java.io.IOException("synthetic-offline")).when(remote).disconnect(any(), any());
        assertThatThrownBy(() -> manager.unbind(enabled.revision())).isInstanceOf(BusinessException.class);
        var stopped = manager.connection();
        assertThat(stopped.enabled()).isFalse();
        assertThat(stopped.bound()).isTrue();
        org.mockito.Mockito.doNothing().when(remote).disconnect(any(), any());
        var removed = manager.unbind(stopped.revision());
        assertThat(removed.bound()).isFalse();
        assertThat(removed.enabled()).isFalse();
        assertThat(Files.readString(temp.resolve("config/cloud-connection.json"))).doesNotContain(credential.credential());
    }

    @Test void legacyCredentialAndExplicitStartupSettingArePreserved() throws Exception {
        manager.stop();
        properties.setEnabled(true);
        properties.setApiUrl("https://cloud.example.test");
        properties.setMqttUrl("ssl://mqtt.example.test:8883");
        CloudAgentFiles.write(Path.of(properties.getCredentialFile()), json.writeValueAsString(credential));
        manager = newManager();
        manager.start();
        assertThat(manager.connection().bound()).isTrue();
        assertThat(manager.connection().enabled()).isTrue();
        assertThat(manager.connection().gatewayId()).isEqualTo(credential.gatewayId());
        manager.reporting(new CloudReportingRequest(0, false));
        var restored = newManager();
        try {
            restored.start();
            assertThat(restored.connection().enabled()).isFalse();
        } finally { restored.stop(); }
    }

    @Test void metadataTimeoutDoesNotLoseConsumedBindingTicket() throws Exception {
        doThrow(new java.io.IOException("synthetic-offline")).when(remote).profile(any(), any());
        assertThat(manager.bind(request(0)).bound()).isTrue();
        assertThat(Files.readString(temp.resolve("config/cloud-connection.json"))).contains(credential.credential());
    }

    @Test void rejectInvalidTlsConfigurationBeforeTicketConsumption() throws Exception {
        var insecure = new CloudBindRequest(0, "http://example.test", "tcp://mqtt.example.test:1883",
                request(0).bindingToken(), "", true);
        assertThatThrownBy(() -> manager.bind(insecure)).isInstanceOf(BusinessException.class);
        verify(remote, never()).bind(any(), any());
        assertThat(manager.connection().bound()).isFalse();
    }

    @Test void failedDurableSaveDoesNotStopExistingReporterOrChangeRevision() throws Exception {
        var bound = manager.bind(request(0));
        var enabled = manager.reporting(new CloudReportingRequest(bound.revision(), true));
        Path file = temp.resolve("config/cloud-connection.json");
        Files.delete(file);
        Files.createDirectory(file);
        assertThatThrownBy(() -> manager.reporting(new CloudReportingRequest(enabled.revision(), false)))
                .isInstanceOf(BusinessException.class);
        assertThat(manager.connection().enabled()).isTrue();
        assertThat(manager.connection().revision()).isEqualTo(enabled.revision());
        verify(agent, never()).stop();
    }

    @Test void unreadableConfigurationDoesNotEnableLegacyReporter() throws Exception {
        manager.stop();
        properties.setEnabled(true);
        CloudAgentFiles.write(temp.resolve("config/cloud-connection.json"), "{invalid");
        manager = newManager();
        manager.start();
        assertThat(manager.connection().enabled()).isFalse();
        assertThat(manager.connection().state()).isEqualTo("CONFIG_ERROR");
        verify(agent, never()).start();
    }

    @Test void invalidCredentialPathCannotPreventLocalApplicationStartup() {
        manager.stop();
        properties.setCredentialFile("invalid\0path");
        manager = newManager();
        manager.start();
        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.connection().state()).isEqualTo("CONFIG_ERROR");
        assertThat(manager.connection().enabled()).isFalse();
        verifyNoInteractions(agent, remote);
    }
}
