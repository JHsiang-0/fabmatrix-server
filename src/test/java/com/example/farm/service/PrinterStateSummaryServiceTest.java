package com.example.farm.service;

import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.model.CanonicalPrinterModelMapper;
import com.example.farm.protocol.observation.PrinterObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrinterStateSummaryServiceTest {
    private final PrinterMapper printers = mock(PrinterMapper.class);
    private final CanonicalPrinterStateService states = mock(CanonicalPrinterStateService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CanonicalPrinterStateService> provider = mock(ObjectProvider.class);
    private final PrinterStateSummaryService service = new PrinterStateSummaryService(printers, provider);

    @BeforeEach void setUp() { when(provider.getIfAvailable()).thenReturn(states); }

    @Test void readsModelsInOneBatchAndUsesModelInsteadOfOldDatabaseStatus() {
        Printer first = printer(42L);
        Printer second = printer(43L);
        second.setConnectionStatus("OFFLINE");
        var items = List.of(first, second);
        Instant now = Instant.now();
        var observation = new PrinterObservation(now, now, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        var model = CanonicalPrinterModelMapper.map(first, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, observation, null, false, now);
        when(states.findLatestBatch(items)).thenReturn(Map.of(42L, model));

        var result = service.views(items);
        assertThat(result.getFirst().getStateSummary().status()).isEqualTo("IDLE");
        assertThat(result.getFirst().getStatus()).isEqualTo("PRINTING");
        assertThat(result.getLast().getStateSummary().status()).isEqualTo("OFFLINE");
        assertThat(result.getLast().getStateSummary().progress()).isNull();
        verify(states).findLatestBatch(items);
        verify(states, never()).findLatest(anyLong());
    }

    @Test void storageFailureReturnsUnavailableSummaryAndDoesNotExposeOldMeasurements() {
        Printer printer = printer(42L);
        when(states.findLatestBatch(List.of(printer))).thenThrow(new IllegalStateException("synthetic-storage-failure"));
        var summary = service.view(printer).getStateSummary();
        assertThat(summary.stale()).isTrue();
        assertThat(summary.status()).isEqualTo("UNKNOWN");
        assertThat(summary.progress()).isNull();
    }

    @Test void responseDecorationDoesNotMutateLegacyCache() {
        var legacy = new MoonrakerStatusDTO();
        legacy.setProgress(98.0);
        var summary = service.view(printer(42L)).getStateSummary();
        var response = service.realtime(legacy, summary);
        assertThat(response).isNotSameAs(legacy);
        assertThat(response.getStateSummary()).isSameAs(summary);
        assertThat(response.getProgress()).isEqualTo(98.0);
        assertThat(legacy.getStateSummary()).isNull();
    }

    private Printer printer(long id) {
        var printer = new Printer();
        printer.setId(id);
        printer.setDeviceUid(UUID.randomUUID().toString());
        printer.setName("Synthetic printer");
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("ONLINE");
        printer.setStatus("PRINTING");
        return printer;
    }
}
