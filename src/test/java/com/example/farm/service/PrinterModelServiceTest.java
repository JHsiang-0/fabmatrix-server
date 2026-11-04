package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.model.CanonicalPrinterModelMapper;
import com.example.farm.protocol.observation.PrinterObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrinterModelServiceTest {
    private final PrinterService printers = mock(PrinterService.class);
    private final CanonicalPrinterStateService states = mock(CanonicalPrinterStateService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CanonicalPrinterStateService> provider = mock(ObjectProvider.class);
    private final PrinterModelService service = new PrinterModelService(printers,
            new ObjectMapper().findAndRegisterModules(), provider);

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidIds(long id) {
        assertThatThrownBy(() -> service.getModel(id)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(400));
    }

    @Test
    void rejectsRemovedPrinter() {
        assertThatThrownBy(() -> service.getModel(42L)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(404));
    }

    @Test
    void returnsExplicitEmptyBeforeFirstObservationOrWithoutLocalProfile() {
        when(printers.getById(42L)).thenReturn(new Printer());
        assertThat(service.getModel(42L).model()).isNull();
        when(provider.getIfAvailable()).thenReturn(states);
        assertThat(service.getModel(42L).stale()).isTrue();
        assertThat(service.getModel(42L).model()).isNull();
    }

    @Test
    void returnsPersistedModelAndServerClock() {
        var model = model(Instant.now());
        prepare(model);
        var result = service.getModel(42L);
        assertThat(result.stale()).isFalse();
        assertThat(result.serverTime()).isAfterOrEqualTo(model.observation().observedAt());
        assertThat(result.model()).containsEntry("modelVersion", 2);
        assertThat(result.model().get("identity").toString()).contains(model.identity().deviceUid().toString());
    }

    @ParameterizedTest
    @ValueSource(longs = {-60, 60})
    void rejectsOldAndFutureMeasurementsAsCurrent(long offset) {
        prepare(model(Instant.now().plusSeconds(offset)));
        assertThat(service.getModel(42L).stale()).isTrue();
    }

    @Test
    void retainsHistoricalSnapshotWithCurrentOfflineHealth() {
        var model = model(Instant.now()).withConnectionHealth(new CanonicalPrinterModel.Connection(
                CanonicalPrinterModel.ConnectionStatus.OFFLINE, true, Instant.now(), 3));
        prepare(model);
        var result = service.getModel(42L);
        assertThat(result.stale()).isTrue();
        assertThat(result.model().get("observation").toString()).contains("OFFLINE");
    }

    @Test
    void rejectsCrossDeviceSnapshotAndPropagatesStorageFailures() {
        var model = model(Instant.now());
        prepare(new CanonicalPrinterModel(model.modelVersion(),
                new CanonicalPrinterModel.Identity(UUID.randomUUID(), UUID.randomUUID(), "43"),
                model.descriptor(), model.observation()));
        assertThatThrownBy(() -> service.getModel(42L)).hasMessage("设备模型身份不一致");
        when(states.findLatest(42)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> service.getModel(42L)).isInstanceOf(IllegalStateException.class);
    }

    private void prepare(CanonicalPrinterModel model) {
        when(printers.getById(42L)).thenReturn(new Printer());
        when(provider.getIfAvailable()).thenReturn(states);
        when(states.findLatest(42)).thenReturn(model);
    }

    private static CanonicalPrinterModel model(Instant at) {
        Printer printer = new Printer();
        printer.setId(42L);
        printer.setName("test");
        printer.setDeviceUid(UUID.randomUUID().toString());
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("ONLINE");
        var observation = new PrinterObservation(at, at, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        return CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, observation, null, false, at);
    }
}
