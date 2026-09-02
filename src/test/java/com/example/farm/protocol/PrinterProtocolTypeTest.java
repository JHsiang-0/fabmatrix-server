package com.example.farm.protocol;

import com.example.farm.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterProtocolTypeTest {

    @Test
    void normalizesSupportedAndHistoricalValues() {
        assertThat(PrinterProtocolType.normalize("Klipper")).isEqualTo(PrinterProtocolType.KLIPPER);
        assertThat(PrinterProtocolType.normalize(" klipper ")).isEqualTo(PrinterProtocolType.KLIPPER);
        assertThat(PrinterProtocolType.normalize("rrf")).isEqualTo(PrinterProtocolType.RRF);
    }

    @Test
    void rejectsBlankAndUnknownValues() {
        assertThatThrownBy(() -> PrinterProtocolType.normalize(" "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> PrinterProtocolType.normalize("OCTOPRINT"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持");
    }
}
