package com.example.farm.entity.vo;

import com.example.farm.entity.Printer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterVOTest {

    @Test
    void shouldUseCommunicationHealthForEffectiveStatusWithoutLosingBusinessStatus() {
        Printer printer = new Printer();
        printer.setStatus("IDLE");
        printer.setLastKnownStatus("IDLE");
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("OFFLINE");

        PrinterVO vo = PrinterVO.from(printer);

        assertThat(vo.getStatus()).isEqualTo("IDLE");
        assertThat(vo.getLastKnownStatus()).isEqualTo("IDLE");
        assertThat(vo.getEffectiveStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void shouldExposeUnknownWhenMonitoringIsDisabledOrConnectionIsUnknown() {
        Printer printer = new Printer();
        printer.setStatus("PRINTING");
        printer.setMonitorEnabled(false);
        printer.setConnectionStatus("ONLINE");
        assertThat(PrinterVO.resolveEffectiveStatus(printer)).isEqualTo("UNKNOWN");

        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("UNKNOWN");
        assertThat(PrinterVO.resolveEffectiveStatus(printer)).isEqualTo("UNKNOWN");

        printer.setConnectionStatus("ONLINE");
        assertThat(PrinterVO.resolveEffectiveStatus(printer)).isEqualTo("PRINTING");
    }
}
