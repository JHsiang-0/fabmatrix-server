package com.example.farm.entity;

import com.example.farm.entity.vo.PrintFileVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PrintFileVOContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesFolderWithUnifiedFolderFieldOnly() throws Exception {
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setIsFolder(true);
        file.setOriginalName("Models");
        file.setFileUrl("http://rustfs:9000/farm/internal-key");
        file.setEstTime(3600);

        String json = objectMapper.writeValueAsString(PrintFileVO.from(file));

        assertThat(json).contains("\"folder\":true");
        assertThat(json).contains("\"estTime\":3600");
        assertThat(json).doesNotContain("isFolder", "fileUrl", "internal-key", "estimatedSeconds");
    }

    @Test
    void serializesUnsetFolderAsFalseForFiles() {
        PrintFile file = new PrintFile();
        file.setId(21L);
        file.setOriginalName("demo.gcode");

        assertThat(PrintFileVO.from(file).getFolder()).isFalse();
    }

    @Test
    void keepsFileMetadataNumericTypesStable() throws Exception {
        PrintFile file = new PrintFile();
        file.setNozzleTemp(210);
        file.setBedTemp(60);
        file.setFilamentLength(new BigDecimal("3.00"));
        file.setSuccessRate(new BigDecimal("98.50"));

        var json = objectMapper.readTree(objectMapper.writeValueAsString(PrintFileVO.from(file)));

        assertThat(json.get("nozzleTemp").isInt()).isTrue();
        assertThat(json.get("bedTemp").isInt()).isTrue();
        assertThat(json.get("filamentLength").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(json.get("successRate").decimalValue()).isEqualByComparingTo("98.50");
    }
}
