package com.example.farm.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentCredentialsConfigTest {

    @TempDir
    Path directory;

    @Test
    void importsLocalCredentialsAndPrefersEnvironmentWithoutStartingInfrastructure() throws Exception {
        Path local = directory.resolve("credentials.local.yaml");
        Files.writeString(local, """
                local.credentials.mysql-password: synthetic-local-mysql
                local.credentials.redis-password: synthetic-local-redis
                local.credentials.rustfs-access-key: synthetic-local-access
                local.credentials.rustfs-secret-key: synthetic-local-secret
                """);
        String shared = new ClassPathResource("application-dev.yaml")
                .getContentAsString(StandardCharsets.UTF_8);
        Path profile = directory.resolve("application-dev.yaml");
        Files.writeString(profile, shared.replace("optional:file:./config/application-dev.local.yaml",
                "optional:" + local.toUri()));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.config.location", profile.toUri().toString())
                .withProperty("spring.profiles.active", "dev")
                .withProperty("MYSQL_PASSWORD", "synthetic-environment-mysql");

        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("spring.datasource.password"))
                .isEqualTo("synthetic-environment-mysql");
        assertThat(environment.getProperty("spring.data.redis.password"))
                .isEqualTo("synthetic-local-redis");
        assertThat(environment.getProperty("rustfs.access-key"))
                .isEqualTo("synthetic-local-access");
        assertThat(environment.getProperty("rustfs.secret-key"))
                .isEqualTo("synthetic-local-secret");
    }
}
