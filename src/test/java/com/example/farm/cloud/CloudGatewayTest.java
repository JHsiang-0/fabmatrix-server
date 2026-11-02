package com.example.farm.cloud;

import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterService;
import com.example.farm.protocol.observation.PrinterObservationParser;
import com.example.farm.protocol.observation.PrinterObservationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CloudGatewayTest {
    @TempDir Path temp;

    @Test void fileWritesAreAtomicBoundedAndOwnerProtected() throws Exception {
        Path file = temp.resolve("nested/credential.json");
        CloudAgentFiles.verifyWritable(file);
        CloudAgentFiles.write(file, "first");
        CloudAgentFiles.write(file, "second");
        assertThat(CloudAgentFiles.read(file, 10)).isEqualTo("second");
        assertThatThrownBy(() -> CloudAgentFiles.read(file, 2)).isInstanceOf(java.io.IOException.class);
        if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        }
        try (var files = Files.list(file.getParent())) { assertThat(files.count()).isEqualTo(1); }
    }

    @Test void remoteApiRequiresHttpsAndRejectsEmbeddedCredentialsAndPaths() {
        for (String url : List.of("http://cloud.example", "https://user:password@cloud.example", "https://cloud.example/path",
                "https://cloud.example?token=redacted", "https://cloud.example#part", "ftp://cloud.example")) {
            assertThatThrownBy(() -> CloudTls.apiUri(url)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(CloudTls.apiUri("https://cloud.example/").toString()).isEqualTo("https://cloud.example");
        assertThat(CloudTls.apiUri("http://127.0.0.1:28091").getHost()).isEqualTo("127.0.0.1");
    }

    @Test void invalidCustomCaNeverFallsBackToTrustAll() throws Exception {
        Path ca = temp.resolve("invalid.crt");
        Files.writeString(ca, "not a certificate");
        assertThatThrownBy(() -> CloudTls.trusted(ca.toString())).isInstanceOf(Exception.class);
    }

    @Test void capturesWhitelistedStateWithoutDeviceCredentialsAddressesOrFileNames() throws Exception {
        PrinterService printers = mock(PrinterService.class);
        PrinterCacheService cache = mock(PrinterCacheService.class);
        Printer asset = asset(1L);
        asset.setApiKey("private-device-key");
        asset.setIpAddress("192.168.0.100");
        asset.setMacAddress("00:11:22:33:44:55");
        asset.setLastSeenAt(LocalDateTime.of(2026, 10, 30, 10, 0));
        var status = new MoonrakerStatusDTO();
        status.setFilename("private-file.gcode");
        status.setProgress(42.5);
        status.setToolTemperature(210.0);
        status.setBedTemperature(60.0);
        when(printers.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(asset));
        when(cache.getCachedStatus(1L)).thenReturn(status);
        var result = new CloudSnapshotSource(printers, cache).capture();
        assertThat(result.getFirst().progress()).isEqualTo(42.5);
        assertThat(result.getFirst().observedAt()).isEqualTo(java.time.Instant.parse("2026-10-30T02:00:00Z"));
        String wire = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(wire).doesNotContain("private-device-key", "192.168.0.100", "00:11:22:33:44:55", "private-file.gcode", "apiKey");
    }

    @Test void unavailableCacheDoesNotBlockAssetSnapshotOrRepeatNetworkFailureForEachDevice() {
        PrinterService printers = mock(PrinterService.class);
        PrinterCacheService cache = mock(PrinterCacheService.class);
        when(printers.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(asset(1L), asset(2L)));
        when(cache.getCachedStatus(1L)).thenThrow(new IllegalStateException("cache unavailable"));
        var result = new CloudSnapshotSource(printers, cache).capture();
        assertThat(result).hasSize(2).allMatch(item -> item.progress() == null);
        verify(cache, never()).getCachedStatus(2L);
    }

    @Test void v2MapsObservationWithoutPrivateFilenameAndV1RemainsUnchanged() throws Exception {
        PrinterService printers = mock(PrinterService.class);
        PrinterCacheService cache = mock(PrinterCacheService.class);
        Printer asset = asset(1L);
        var at = java.time.Instant.now();
        var node = new ObjectMapper().readTree("""
                {"webhooks":{"state":"ready"},"print_stats":{"state":"printing","filename":"private.gcode"},
                 "extruder":{"temperature":0,"target":210},"display_status":{"progress":0}}
                """);
        var observation = PrinterObservationParser.klipper(node, at, at);
        when(printers.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(asset));
        when(cache.getObservationView(eq(asset), any(), any())).thenReturn(new PrinterObservationView(
                PrinterObservationView.Availability.CURRENT, observation, observation));
        var source = new CloudSnapshotSource(printers, cache);
        var json = new ObjectMapper().findAndRegisterModules();
        String v2 = json.writeValueAsString(source.captureV2());
        assertThat(v2).contains("PARTIAL", "currentC", "GOOD").doesNotContain("private.gcode", "filename");
        assertThat(json.writeValueAsString(source.capture())).doesNotContain("observation");
    }

    @Test void rejectsOversizedFarmWithoutSilentlyDroppingDevices() {
        PrinterService printers = mock(PrinterService.class);
        when(printers.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(java.util.stream.LongStream.rangeClosed(1, 201).mapToObj(this::asset).toList());
        assertThatThrownBy(() -> new CloudSnapshotSource(printers, mock(PrinterCacheService.class)).capture())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void invalidCloudConfigurationDoesNotPreventLocalApplicationLifecycle() throws Exception {
        var properties = new CloudGatewayProperties();
        properties.setApiUrl("http://untrusted.invalid");
        var agent = new CloudGatewayAgent(properties, mock(CloudSnapshotSource.class));
        try {
            agent.start();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(4).toNanos();
            while ("STARTING".equals(agent.status().state()) && System.nanoTime() < deadline) { Thread.sleep(20); }
            assertThat(agent.status().state()).isEqualTo("RETRYING");
            assertThat(agent.isRunning()).isTrue();
        } finally { agent.stop(); }
        assertThat(agent.status().state()).isEqualTo("STOPPED");
    }

    private Printer asset(long id) {
        var printer = new Printer();
        printer.setId(id);
        printer.setName("Printer " + id);
        printer.setFirmwareType("KLIPPER");
        printer.setConnectionStatus("ONLINE");
        printer.setStatus("IDLE");
        printer.setMonitorEnabled(true);
        return printer;
    }
}
