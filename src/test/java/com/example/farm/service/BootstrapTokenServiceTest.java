package com.example.farm.service;

import com.example.farm.entity.dto.BootstrapTokenDTO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapTokenServiceTest {

    @Test
    void readsOnlyUnexpiredCredentialAndComparesItConstantTime() throws Exception {
        Path tokenFile = Files.createTempFile("fabmatrix-bootstrap-", ".token");
        try {
            Files.writeString(tokenFile, "expiresAt=2000\ntoken=local-bootstrap-token\n", StandardCharsets.UTF_8);
            BootstrapTokenService service = new BootstrapTokenService(
                    tokenFile, Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));

            assertThat(service.getValidCredential().getToken()).isEqualTo("local-bootstrap-token");
            assertThat(service.matches("local-bootstrap-token")).isTrue();
            assertThat(service.matches("wrong-token")).isFalse();
        } finally {
            Files.deleteIfExists(tokenFile);
        }
    }

    @Test
    void expiredOrMalformedCredentialIsUnavailableAndCanBeInvalidated() throws Exception {
        Path tokenFile = Files.createTempFile("fabmatrix-bootstrap-", ".token");
        try {
            Files.writeString(tokenFile, "expiresAt=1000\ntoken=expired\n", StandardCharsets.UTF_8);
            BootstrapTokenService expired = new BootstrapTokenService(
                    tokenFile, Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
            assertThat(expired.getValidCredential()).isNull();

            Files.writeString(tokenFile, "not-a-credential\n", StandardCharsets.UTF_8);
            assertThat(expired.matches("expired")).isFalse();
            expired.invalidate();
            assertThat(Files.exists(tokenFile)).isFalse();
        } finally {
            Files.deleteIfExists(tokenFile);
        }
    }

    @Test
    void missingCredentialIsCreatedWithShortExpiry() throws Exception {
        Path directory = Files.createTempDirectory("fabmatrix-bootstrap-");
        Path tokenFile = directory.resolve("bootstrap.token");
        try {
            BootstrapTokenService service = new BootstrapTokenService(
                    tokenFile, Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));

            BootstrapTokenDTO credential = service.getOrRotateCredential();

            assertThat(credential).isNotNull();
            assertThat(credential.getToken()).hasSizeGreaterThan(40);
            assertThat(credential.getExpiresAt()).isEqualTo(1000L + 30 * 60 * 1000);
            assertThat(service.matches(credential.getToken())).isTrue();
        } finally {
            Files.deleteIfExists(tokenFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void expiredCredentialIsRotatedAndOldTokenStopsMatching() throws Exception {
        Path tokenFile = Files.createTempFile("fabmatrix-bootstrap-", ".token");
        try {
            Files.writeString(tokenFile, "expiresAt=1000\ntoken=old-token\n", StandardCharsets.UTF_8);
            BootstrapTokenService service = new BootstrapTokenService(
                    tokenFile, Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC));

            BootstrapTokenDTO rotated = service.getOrRotateCredential();

            assertThat(rotated.getToken()).isNotEqualTo("old-token");
            assertThat(service.matches("old-token")).isFalse();
            assertThat(service.matches(rotated.getToken())).isTrue();
        } finally {
            Files.deleteIfExists(tokenFile);
        }
    }

    @Test
    void concurrentRotationReturnsOneCredentialAndLeavesCompleteFile() throws Exception {
        Path directory = Files.createTempDirectory("fabmatrix-bootstrap-");
        Path tokenFile = directory.resolve("bootstrap.token");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            BootstrapTokenService service = new BootstrapTokenService(
                    tokenFile, Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
            List<Future<BootstrapTokenDTO>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.getOrRotateCredential();
                }));
            }
            ready.await();
            start.countDown();

            List<BootstrapTokenDTO> credentials = new ArrayList<>();
            for (Future<BootstrapTokenDTO> future : futures) {
                credentials.add(future.get());
            }

            assertThat(credentials).allMatch(credential -> credential != null);
            assertThat(credentials).extracting(BootstrapTokenDTO::getToken).containsOnly(
                    credentials.get(0).getToken());
            assertThat(Files.readString(tokenFile, StandardCharsets.UTF_8))
                    .contains("expiresAt=", "token=" + credentials.get(0).getToken());
        } finally {
            executor.shutdownNow();
            Files.deleteIfExists(tokenFile);
            Files.deleteIfExists(directory);
        }
    }
}
