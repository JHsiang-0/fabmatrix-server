package com.example.farm.protocol;

import com.example.farm.common.utils.MoonrakerApiClient;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.protocol.observation.PrinterObservationParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KlipperMoonrakerAdapterTest {

    @Mock
    private MoonrakerApiClient moonrakerApiClient;

    @Test
    void mapsMoonrakerPrintingStatusToUnifiedStatus() {
        MoonrakerStatusDTO source = new MoonrakerStatusDTO();
        source.setSystemState("ready");
        source.setState("printing");
        source.setProgress(35.5);
        source.setToolTemperature(210.0);
        source.setBedTemperature(60.0);
        when(moonrakerApiClient.getPrinterStatus(org.mockito.ArgumentMatchers.any(PrinterEndpoint.class))).thenReturn(source);

        PrinterDeviceStatus result = adapter().getStatus(endpoint());

        assertThat(result.status()).isEqualTo(PrinterStatus.PRINTING);
        assertThat(result.progress()).isEqualByComparingTo("35.5");
        assertThat(result.toolTemperature()).isEqualByComparingTo("210.0");
    }

    @Test
    void mapsKlipperSystemErrorToError() {
        MoonrakerStatusDTO source = new MoonrakerStatusDTO();
        source.setSystemState("shutdown");
        source.setState("standby");
        when(moonrakerApiClient.getPrinterStatus(org.mockito.ArgumentMatchers.any(PrinterEndpoint.class))).thenReturn(source);

        assertThat(adapter().getStatus(endpoint()).status()).isEqualTo(PrinterStatus.ERROR);
    }

    @Test
    void exposesOnlyGoodFileCountersFromTheSameKlipperObservationForExecutionChecks() throws Exception {
        var source = new MoonrakerStatusDTO(); source.setSystemState("ready"); source.setState("printing");
        var now = Instant.now();
        source.setObservation(PrinterObservationParser.klipper(new ObjectMapper().readTree("""
                {"webhooks":{"state":"ready"},"print_stats":{"state":"printing","filename":"part.gcode"},
                 "virtual_sdcard":{"file_position":1200,"file_size":10000,"progress":0.12}}
                """), now, now));
        when(moonrakerApiClient.getPrinterStatus(org.mockito.ArgumentMatchers.any(PrinterEndpoint.class))).thenReturn(source);
        assertThat(adapter().getStatus(endpoint()).filePosition()).isEqualByComparingTo("1200");
        assertThat(adapter().getStatus(endpoint()).fileSize()).isEqualByComparingTo("10000");
        source.setObservation(PrinterObservationParser.klipper(new ObjectMapper().readTree("""
                {"webhooks":{"state":"ready"},"print_stats":{"state":"printing"},
                 "virtual_sdcard":{"file_position":-1,"file_size":-1}}
                """), now, now));
        assertThat(adapter().getStatus(endpoint()).filePosition()).isNull();
        assertThat(adapter().getStatus(endpoint()).fileSize()).isNull();
    }

    @Test
    void convertsEmptyResponseToOfflineProtocolFailure() {
        when(moonrakerApiClient.getPrinterStatus(org.mockito.ArgumentMatchers.any(PrinterEndpoint.class))).thenReturn(null);

        assertThatThrownBy(() -> adapter().getStatus(endpoint()))
                .isInstanceOf(PrinterProtocolException.class)
                .satisfies(error -> {
                    PrinterProtocolException exception = (PrinterProtocolException) error;
                    assertThat(exception.getCategory()).isEqualTo(FailureCategory.OFFLINE);
                    assertThat(exception.getOperation()).isEqualTo(PrinterOperation.GET_STATUS);
                });
    }

    private KlipperMoonrakerAdapter adapter() {
        return new KlipperMoonrakerAdapter(moonrakerApiClient);
    }

    private PrinterEndpoint endpoint() {
        return new PrinterEndpoint(403L, "192.168.1.80", "secret-not-logged", PrinterProtocolType.KLIPPER);
    }
}
