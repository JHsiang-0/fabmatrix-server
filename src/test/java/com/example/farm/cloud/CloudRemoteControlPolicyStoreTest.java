package com.example.farm.cloud;

import com.example.farm.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudRemoteControlPolicyStoreTest {
    @TempDir Path temporary;

    @Test void startsDisabledAndRequiresExplicitVersionAndRevisionForEveryBinding() {
        var properties = properties(); var store = new CloudRemoteControlPolicyStore(properties); var first = credential(1);
        assertThat(store.view(first).remoteEnabled()).isFalse();
        assertThat(store.view(first).supportedActions()).isEqualTo(CloudControlWire.ACTIONS);
        assertThatThrownBy(() -> store.updateUnified(null, true, 1, 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.updateUnified(first, true, 2, 1)).isInstanceOf(BusinessException.class);
        assertThat(store.updateUnified(first, true, 1, 1).remoteEnabled()).isTrue();
        var saved = new CloudRemoteControlPolicyStore(properties).view(first);
        assertThat(saved.remoteEnabled()).isTrue(); assertThat(saved.enabled()).isTrue(); assertThat(saved.temperatureEnabled()).isTrue();
        assertThatThrownBy(() -> store.updateUnified(first, false, 1, 1)).isInstanceOf(BusinessException.class);
        var rebound = new CloudWire.Credential(first.gatewayId(), first.organizationId(), first.farmId(), 2,
                first.credential(), first.expiresAt(), first.mqttClientId(), first.mqttUsername(), first.topicPrefix());
        assertThat(store.view(rebound).remoteEnabled()).isFalse(); store.bindingChanged(first, rebound);
        assertThat(store.view(rebound).revision()).isEqualTo(3); assertThat(store.view(rebound).remoteEnabled()).isFalse();
        store.bindingChanged(rebound, null); store.bindingChanged(null, first);
        assertThat(store.view(first).remoteEnabled()).isFalse();
    }

    @Test void legacyGrantsRequireExplicitReauthorizationAndCannotSilentlyBecomeFullControl() throws Exception {
        var binding = credential(1);
        Files.writeString(temporary.resolve("cloud-remote-control.json"), "{\"enabled\":true,\"temperatureEnabled\":true,\"revision\":2,\"gatewayId\":\"" + binding.gatewayId() + "\",\"bindingVersion\":1}");
        var store = new CloudRemoteControlPolicyStore(properties()); var view = store.view(binding);
        assertThat(view.remoteEnabled()).isFalse(); assertThat(view.enabled()).isFalse(); assertThat(view.temperatureEnabled()).isFalse();
        assertThat(view.requiresReauthorization()).isTrue();
        assertThatThrownBy(() -> store.update(binding, true, null, 2)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.update(binding, null, true, 2)).isInstanceOf(BusinessException.class);
        assertThat(store.updateUnified(binding, true, 1, 2).remoteEnabled()).isTrue();
        assertThat(store.view(binding).requiresReauthorization()).isFalse();
        assertThat(store.update(binding, false, null, 3).remoteEnabled()).isFalse();
        assertThat(store.view(binding).temperatureEnabled()).isFalse();
    }

    @Test void unifiedAuthorizationAllowsExactlyTheVersionOneCatalog() {
        var binding = credential(1); var store = new CloudRemoteControlPolicyStore(properties()); store.updateUnified(binding, true, 1, 1);
        var session = new CloudControlWire.Session(binding.gatewayId(), 1, UUID.randomUUID().toString(), 1, UUID.randomUUID().toString());
        for (String action : CloudControlWire.ACTIONS) assertThat(store.allows(session, 2, action)).isTrue();
        assertThat(store.allows(session, 2, "EMERGENCY_STOP")).isFalse(); assertThat(store.allows(session, 1, "PAUSE")).isFalse();
    }

    @Test void corruptPolicyFailsClosedInsteadOfAffectingLocalProduction() throws Exception {
        Files.writeString(temporary.resolve("cloud-remote-control.json"), "{invalid"); var store = new CloudRemoteControlPolicyStore(properties());
        assertThatThrownBy(() -> store.view(credential(1))).isInstanceOf(BusinessException.class);
        assertThat(store.allows(new CloudControlWire.Session(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), 1, UUID.randomUUID().toString()), 1)).isFalse();
    }

    private CloudGatewayProperties properties() {
        var properties = new CloudGatewayProperties(); properties.setCredentialFile(temporary.resolve("gateway.json").toString()); return properties;
    }
    static CloudWire.Credential credential(long version) {
        return new CloudWire.Credential(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), version,
                "isolated-test-only", System.currentTimeMillis() + 3600000, "fixture", "fixture", "fixture");
    }
}
