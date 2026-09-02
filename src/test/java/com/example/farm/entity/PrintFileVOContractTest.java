package com.example.farm.entity;

import com.example.farm.entity.vo.PrintFileVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrintFileVOContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesFolderWithUnifiedFolderFieldOnly() throws Exception {
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setIsFolder(true);
        file.setOriginalName("Models");

        String json = objectMapper.writeValueAsString(PrintFileVO.from(file));

        assertThat(json).contains("\"folder\":true");
        assertThat(json).doesNotContain("isFolder");
    }
}
