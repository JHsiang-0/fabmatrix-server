package com.example.farm.protocol;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterProtocolAdapterFactoryTest {

    @Test
    void selectsAdapterUsingNormalizedProtocolType() {
        StubAdapter klipper = new StubAdapter(PrinterProtocolType.KLIPPER);
        StubAdapter rrf = new StubAdapter(PrinterProtocolType.RRF);
        PrinterProtocolAdapterFactory factory = new PrinterProtocolAdapterFactory(List.of(klipper, rrf));

        assertThat(factory.getAdapter("Klipper")).isSameAs(klipper);
        assertThat(factory.getAdapter("rrf")).isSameAs(rrf);
        assertThat(factory.supports("KLIPPER")).isTrue();
    }

    @Test
    void doesNotFallbackToKlipperWhenRrfAdapterIsNotRegistered() {
        StubAdapter klipper = new StubAdapter(PrinterProtocolType.KLIPPER);
        PrinterProtocolAdapterFactory factory = new PrinterProtocolAdapterFactory(List.of(klipper));

        assertThatThrownBy(() -> factory.getAdapter("RRF"))
                .isInstanceOf(PrinterProtocolException.class)
                .hasMessageContaining("暂未接入")
                .satisfies(error -> assertThat(((PrinterProtocolException) error).getCategory())
                        .isEqualTo(FailureCategory.UNSUPPORTED));
    }

    @Test
    void rejectsDuplicateAdaptersForSameProtocol() {
        assertThatThrownBy(() -> new PrinterProtocolAdapterFactory(List.of(
                new StubAdapter(PrinterProtocolType.KLIPPER),
                new StubAdapter(PrinterProtocolType.KLIPPER))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多个");
    }

    private static final class StubAdapter implements PrinterProtocolAdapter {

        private final PrinterProtocolType type;

        private StubAdapter(PrinterProtocolType type) {
            this.type = type;
        }

        @Override
        public PrinterProtocolType protocolType() {
            return type;
        }

        @Override
        public PrinterDeviceStatus getStatus(PrinterEndpoint endpoint) {
            return null;
        }

        @Override
        public void pause(PrinterEndpoint endpoint) {
        }

        @Override
        public void resume(PrinterEndpoint endpoint) {
        }

        @Override
        public void cancel(PrinterEndpoint endpoint) {
        }

        @Override
        public void emergencyStop(PrinterEndpoint endpoint) {
        }

        @Override
        public void uploadFile(PrinterEndpoint endpoint, Resource file, String filename, boolean startPrint) {
        }
    }
}
