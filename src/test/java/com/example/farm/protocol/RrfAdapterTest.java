package com.example.farm.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RrfAdapterTest {

    @Mock
    private RrfApiClient rrfApiClient;

    @Test
    void mapsRrfProcessingStateWithoutUsingMoonraker() {
        when(rrfApiClient.getStatus(endpoint())).thenReturn(new RrfStatusResponse(
                "processing", null, "demo.gcode", BigDecimal.valueOf(42.5),
                null, null, null, null, null, null, null));

        PrinterDeviceStatus result = adapter().getStatus(endpoint());

        assertThat(result.status()).isEqualTo(PrinterStatus.PRINTING);
        assertThat(result.rawState()).isEqualTo("processing");
        assertThat(result.progress()).isEqualByComparingTo("42.5");
    }

    @Test
    void mapsOfficialStatusCodeWhenObjectModelStateIsMissing() {
        PrinterDeviceStatus result = adapter().toDeviceStatus(new RrfStatusResponse(
                null, "S", null, null, null, null, null, null, null, null, null));

        assertThat(result.status()).isEqualTo(PrinterStatus.PAUSED);
        assertThat(result.rawState()).isEqualTo("paused");
    }

    @Test
    void preservesRrfTerminalEvidenceInUnifiedSnapshot() {
        PrinterDeviceStatus result = adapter().toDeviceStatus(new RrfStatusResponse(
                "idle", null, "demo.gcode", BigDecimal.valueOf(100), null, null,
                null, null, null, null, null, BigDecimal.valueOf(1000),
                BigDecimal.valueOf(1000), BigDecimal.ZERO, false, false));

        assertThat(result.filePosition()).isEqualByComparingTo("1000");
        assertThat(result.fileSize()).isEqualByComparingTo("1000");
        assertThat(result.timesLeft()).isEqualByComparingTo("0");
        assertThat(result.lastFileCancelled()).isFalse();
        assertThat(result.lastFileAborted()).isFalse();
    }

    @Test
    void routesPauseThroughRrfClientAndPreservesUnsupportedError() {
        PrinterProtocolException unsupported = new PrinterProtocolException(
                PrinterOperation.PAUSE, PrinterProtocolType.RRF,
                FailureCategory.UNSUPPORTED, "RRF 真实 HTTP 能力尚未实现，请先完成设备协议确认");
        doThrow(unsupported).when(rrfApiClient)
                .executeGcode(endpoint(), "M25", PrinterOperation.PAUSE);

        assertThatThrownBy(() -> adapter().pause(endpoint()))
                .isSameAs(unsupported);
        verify(rrfApiClient).executeGcode(any(), org.mockito.ArgumentMatchers.eq("M25"),
                org.mockito.ArgumentMatchers.eq(PrinterOperation.PAUSE));
    }

    @Test
    void pausesBeforeCancellingAnActiveRrfPrint() {
        when(rrfApiClient.getStatus(endpoint()))
                .thenReturn(status("processing"), status("pausing"), status("paused"), status("idle"));

        adapter().cancel(endpoint());

        var ordered = inOrder(rrfApiClient);
        ordered.verify(rrfApiClient).getStatus(endpoint());
        ordered.verify(rrfApiClient).executeGcode(endpoint(), "M25", PrinterOperation.CANCEL);
        ordered.verify(rrfApiClient, org.mockito.Mockito.times(2)).getStatus(endpoint());
        ordered.verify(rrfApiClient).executeGcode(endpoint(), "M0", PrinterOperation.CANCEL);
        ordered.verify(rrfApiClient).getStatus(endpoint());
    }

    @Test
    void doesNotSendCancelCommandWhenRrfIsAlreadyIdle() {
        when(rrfApiClient.getStatus(endpoint())).thenReturn(status("idle"));

        adapter().cancel(endpoint());

        verify(rrfApiClient, never()).executeGcode(any(), any(), any());
    }

    @Test
    void startsAnAlreadyUploadedRrfFileWithoutUploadingAgain() {
        adapter().startFile(endpoint(), "folder/demo.gcode");

        verify(rrfApiClient).startFile(endpoint(), "folder/demo.gcode");
    }

    private RrfStatusResponse status(String state) {
        return new RrfStatusResponse(state, null, "demo.gcode", null,
                null, null, null, null, null, null, null);
    }

    private RrfAdapter adapter() {
        return new RrfAdapter(rrfApiClient);
    }

    private PrinterEndpoint endpoint() {
        return new PrinterEndpoint(403L, "192.168.1.80", "device-password", PrinterProtocolType.RRF);
    }
}
