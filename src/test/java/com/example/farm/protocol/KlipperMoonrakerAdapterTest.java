package com.example.farm.protocol;

import com.example.farm.common.utils.MoonrakerApiClient;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        when(moonrakerApiClient.getPrinterStatus("192.168.1.80")).thenReturn(source);

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
        when(moonrakerApiClient.getPrinterStatus("192.168.1.80")).thenReturn(source);

        assertThat(adapter().getStatus(endpoint()).status()).isEqualTo(PrinterStatus.ERROR);
    }

    @Test
    void convertsEmptyResponseToOfflineProtocolFailure() {
        when(moonrakerApiClient.getPrinterStatus("192.168.1.80")).thenReturn(null);

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
