package com.example.farm.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.example.farm.service.AuditLogService;
import java.nio.file.Path;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CloudGatewayConfigurationTest {
    @TempDir Path temp;
    private final CloudSnapshotSource source = mock(CloudSnapshotSource.class);

    private ApplicationContextRunner runner(boolean enabled) {
        return new ApplicationContextRunner().withUserConfiguration(CloudConnectionManager.class, CloudRemoteControlPolicyStore.class)
                .withBean(CloudGatewayProperties.class, () -> {
                    var properties = new CloudGatewayProperties();
                    properties.setEnabled(enabled);
                    properties.setCredentialFile(temp.resolve("config/cloud-gateway.json").toString());
                    properties.setStateDirectory(temp.resolve("state").toString());
                    return properties;
                })
                .withBean(CloudSnapshotSource.class, () -> source)
                .withBean(AuditLogService.class, () -> mock(AuditLogService.class))
                .withBean(CloudConnectionClient.class, () -> mock(CloudConnectionClient.class));
    }

    @Test
    void reportingIsAbsentByDefaultAndWhenExplicitlyDisabled() {
        runner(false).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CloudConnectionManager.class);
            assertThat(context.getBean(CloudConnectionManager.class).status().state()).isEqualTo("DISABLED");
            verifyNoInteractions(source);
        });
    }

    @Test
    void reportingRequiresExplicitEnablement() {
        runner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CloudConnectionManager.class).isRunning()).isTrue();
            assertThat(context.getBean(CloudConnectionManager.class).status().state()).isEqualTo("STARTING");
        });
    }
}
