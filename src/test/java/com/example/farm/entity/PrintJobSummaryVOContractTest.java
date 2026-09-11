package com.example.farm.entity;

import com.example.farm.controller.PrintJobController;
import com.example.farm.entity.vo.PrintJobDetailVO;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PrintJobSummaryVOContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesLongIdsAsStringsAndNozzleSizeAsNumber() throws Exception {
        PrintJobSummaryVO summary = new PrintJobSummaryVO();
        summary.setId(9007199254740991L);
        summary.setFileId(1001L);
        summary.setPrinterId(2002L);
        summary.setUserId(3003L);
        summary.setOperatorId(4004L);
        summary.setRetryOfJobId(5005L);
        summary.setRootJobId(6006L);
        summary.setNozzleSize(new BigDecimal("0.40"));

        var json = objectMapper.readTree(objectMapper.writeValueAsString(summary));

        assertThat(json.get("id").isTextual()).isTrue();
        assertThat(json.get("fileId").isTextual()).isTrue();
        assertThat(json.get("printerId").isTextual()).isTrue();
        assertThat(json.get("userId").isTextual()).isTrue();
        assertThat(json.get("operatorId").isTextual()).isTrue();
        assertThat(json.get("retryOfJobId").isTextual()).isTrue();
        assertThat(json.get("rootJobId").isTextual()).isTrue();
        assertThat(json.get("nozzleSize").isNumber()).isTrue();
        assertThat(json.get("nozzleSize").decimalValue()).isEqualByComparingTo("0.40");
    }

    @Test
    void exposesDedicatedReadModelsAndNullDataWriteTypes() throws Exception {
        assertThat(returnType("getQueue")).contains("PrintJobSummaryVO");
        assertThat(returnType("queryJobs")).contains("PrintJobSummaryVO");
        assertThat(returnType("getById")).contains("PrintJobDetailVO");
        assertThat(returnType("assignJobSafe")).endsWith("Result<java.lang.Void>");
        assertThat(returnType("confirmPrinterSafe")).endsWith("Result<java.lang.Void>");
        assertThat(returnType("startPrint")).endsWith("Result<java.lang.Void>");
        assertThat(returnType("reconcileJob")).endsWith("Result<java.lang.Void>");
        assertThat(PrintJobDetailVO.class.getSuperclass()).isEqualTo(PrintJobSummaryVO.class);
    }

    private String returnType(String methodName) {
        for (Method method : PrintJobController.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method.getGenericReturnType().getTypeName();
            }
        }
        throw new AssertionError("missing method: " + methodName);
    }
}
