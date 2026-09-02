package com.example.farm.entity;

import com.example.farm.entity.vo.PrintJobVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PrintJobVOContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesProgressAsDecimalNumber() throws Exception {
        PrintJob job = new PrintJob();
        job.setId(1001L);
        job.setStatus("PRINTING");
        job.setProgress(new BigDecimal("35.50"));

        var json = objectMapper.readTree(objectMapper.writeValueAsString(PrintJobVO.from(job)));

        assertThat(json.get("progress").isNumber()).isTrue();
        assertThat(json.get("progress").decimalValue()).isEqualByComparingTo("35.50");
    }
}
