package com.example.farm.cloud;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CloudGatewayConfigurationTest {
    private final CloudSnapshotSource source = mock(CloudSnapshotSource.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CloudGatewayAgent.class)
            .withBean(CloudGatewayProperties.class, CloudGatewayProperties::new)
            .withBean(CloudSnapshotSource.class, () -> source);

    @Test
    void reportingIsAbsentByDefaultAndWhenExplicitlyDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(CloudGatewayAgent.class);
            verifyNoInteractions(source);
        });
        runner.withPropertyValues("farm.cloud.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(CloudGatewayAgent.class);
            verifyNoInteractions(source);
        });
    }

    @Test
    void reportingRequiresExplicitEnablement() {
        runner.withPropertyValues("farm.cloud.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CloudGatewayAgent.class);
            assertThat(context.getBean(CloudGatewayAgent.class).isRunning()).isTrue();
        });
    }
}
