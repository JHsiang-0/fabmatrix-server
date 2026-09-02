package com.example.farm.service;

import com.example.farm.entity.Printer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FarmStatusSnapshotServiceTest {

    @Test
    void buildsSafeSnapshotWithAllPrinters() {
        PrinterService printerService = mock(PrinterService.class);
        Printer printer = new Printer();
        printer.setId(403L);
        printer.setName("Printer_C0DA");
        printer.setIpAddress("192.168.1.80");
        printer.setApiKey("must-not-leak");
        printer.setStatus("IDLE");
        when(printerService.list()).thenReturn(List.of(printer));

        Map<String, Object> snapshot = new FarmStatusSnapshotService(printerService).buildSnapshot();

        assertThat(snapshot).containsKey("printers");
        assertThat(snapshot.get("printers")).asList().hasSize(1);
        assertThat(snapshot.toString()).doesNotContain("must-not-leak");
        assertThat(snapshot.toString()).contains("Printer_C0DA");
    }

    @Test
    void buildsEmptySnapshotWhenFarmHasNoPrinters() {
        PrinterService printerService = mock(PrinterService.class);
        when(printerService.list()).thenReturn(List.of());

        Map<String, Object> snapshot = new FarmStatusSnapshotService(printerService).buildSnapshot();

        assertThat(snapshot).containsEntry("printers", List.of());
    }
}
