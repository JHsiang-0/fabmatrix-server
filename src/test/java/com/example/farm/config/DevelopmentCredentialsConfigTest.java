package com.example.farm.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentCredentialsConfigTest {
    @Test
    void developmentUsesLocalProfileInsteadOfExternalServices() throws Exception {
        String shared = new ClassPathResource("application.yaml").getContentAsString(StandardCharsets.UTF_8);
        String development = new ClassPathResource("application-dev.yaml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(shared).contains("dev: local");
        assertThat(development).doesNotContain("jdbc:mysql:", "rustfs:", "redis:");
    }
}
