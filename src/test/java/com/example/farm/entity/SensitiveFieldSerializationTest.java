package com.example.farm.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveFieldSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void entitySerializationDoesNotExposeSensitiveFields() throws Exception {
        Printer printer = new Printer();
        printer.setApiKey("printer-secret");

        PrintFile file = new PrintFile();
        file.setRustfsKey("internal/rustfs-key");

        User user = new User();
        user.setPasswordHash("$2a$secret-hash");

        assertThat(objectMapper.writeValueAsString(printer)).doesNotContain("printer-secret");
        assertThat(objectMapper.writeValueAsString(file)).doesNotContain("internal/rustfs-key");
        assertThat(objectMapper.writeValueAsString(user)).doesNotContain("secret-hash");
    }
}
