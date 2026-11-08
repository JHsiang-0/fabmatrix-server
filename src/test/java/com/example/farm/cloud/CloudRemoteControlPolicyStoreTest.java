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

    @Test void startsDisabledAndRequiresExplicitRevisionForEveryBinding() {
        var properties = properties();
        var store = new CloudRemoteControlPolicyStore(properties);
        var first = credential(1);
        assertThat(store.view(first)).isEqualTo(new CloudRemoteControlPolicyStore.View(false, 1));
        assertThatThrownBy(() -> store.update(null, true, 1)).isInstanceOf(BusinessException.class);
        assertThat(store.update(first, true, 1)).isEqualTo(new CloudRemoteControlPolicyStore.View(true, 2));
        assertThat(new CloudRemoteControlPolicyStore(properties).view(first).enabled()).isTrue();
        assertThatThrownBy(() -> store.update(first, false, 1)).isInstanceOf(BusinessException.class);
        var rebound = new CloudWire.Credential(first.gatewayId(), first.organizationId(), first.farmId(), 2,
                first.credential(), first.expiresAt(), first.mqttClientId(), first.mqttUsername(), first.topicPrefix());
        assertThat(store.view(rebound).enabled()).isFalse();
        store.bindingChanged(first, rebound);
        assertThat(store.view(rebound)).isEqualTo(new CloudRemoteControlPolicyStore.View(false, 3));
        assertThat(store.allows(new CloudControlWire.Session(first.gatewayId(), 1, UUID.randomUUID().toString(), 1,
                UUID.randomUUID().toString()), 2)).isFalse();
        store.bindingChanged(rebound, null);
        store.bindingChanged(null, first);
        assertThat(store.view(first).enabled()).isFalse();
    }

    @Test void corruptPolicyFailsClosedInsteadOfEnablingLocalOrCloudActions() throws Exception {
        var properties = properties();
        Files.writeString(temporary.resolve("cloud-remote-control.json"), "{invalid");
        var store = new CloudRemoteControlPolicyStore(properties);
        assertThatThrownBy(() -> store.view(credential(1))).isInstanceOf(BusinessException.class);
        assertThat(store.allows(new CloudControlWire.Session(UUID.randomUUID().toString(), 1,
                UUID.randomUUID().toString(), 1, UUID.randomUUID().toString()), 1)).isFalse();
    }

    private CloudGatewayProperties properties() {
        var properties = new CloudGatewayProperties();
        properties.setCredentialFile(temporary.resolve("gateway.json").toString());
        return properties;
    }
    static CloudWire.Credential credential(long version) {
        return new CloudWire.Credential(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), version, "isolated-test-only", System.currentTimeMillis() + 3600000,
                "fixture", "fixture", "fixture");
    }
}
