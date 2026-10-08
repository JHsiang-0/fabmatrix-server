package com.example.farm.config;

import com.example.farm.service.PrinterRediscoveryService;
import com.example.farm.task.PrinterRediscoveryTask;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PrinterRediscoveryTaskConfigurationTest {

    @ParameterizedTest
    @CsvSource({"true,true,true", "true,false,false", "false,true,false", "false,false,false"})
    void automaticDiscoveryRequiresBothDiscoveryAndMonitoring(String discovery, String monitor, boolean enabled) {
        PrinterRediscoveryService service = mock(PrinterRediscoveryService.class);
        new ApplicationContextRunner().withUserConfiguration(PrinterRediscoveryTask.class)
                .withBean(PrinterRediscoveryService.class, () -> service)
                .withPropertyValues("farm.discovery.enabled=" + discovery,
                        "farm.monitor.enabled=" + monitor, "farm.scheduler.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    if (enabled) {
                        assertThat(context).hasSingleBean(PrinterRediscoveryTask.class);
                    } else {
                        assertThat(context).doesNotHaveBean(PrinterRediscoveryTask.class);
                    }
                    verifyNoInteractions(service);
                });
    }

    @ParameterizedTest
    @CsvSource({"farm.monitor.enabled=true", "farm.discovery.enabled=true", "farm.scheduler.enabled=false"})
    void missingEnableFlagDoesNotLoadAnAutomaticNetworkTask(String property) {
        new ApplicationContextRunner().withUserConfiguration(PrinterRediscoveryTask.class)
                .withBean(PrinterRediscoveryService.class, () -> mock(PrinterRediscoveryService.class))
                .withPropertyValues(property)
                .run(context -> assertThat(context).doesNotHaveBean(PrinterRediscoveryTask.class));
    }
}
