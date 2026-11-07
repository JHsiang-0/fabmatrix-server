package com.example.farm.cloud;

import com.example.farm.mapper.GCodeMetadataStore;
import com.example.farm.protocol.file.GCodeFileInspection;
import com.example.farm.protocol.file.GCodeFileInspector;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFileMetadataSyncTest {
    @Test void retriesInterruptedAssetUploadAfterRestartAndAcknowledgesOnlyTheCompleteBundle() throws Exception {
        var entry = entry();
        var source = mock(CloudSnapshotSource.class);
        var store = mock(GCodeMetadataStore.class);
        when(source.sourceInstanceId()).thenReturn(UUID.randomUUID().toString());
        when(source.matchesCurrentIdentity(1L, "device-1")).thenReturn(true);
        var acknowledged = new AtomicBoolean();
        when(store.pending(anyString(), anyLong(), anyString(), eq(true)))
                .thenAnswer(invocation -> acknowledged.get() ? List.of() : List.of(entry));
        when(store.pending(anyString(), anyLong(), anyString(), eq(false))).thenReturn(List.of());
        when(store.images(entry)).thenReturn(entry.inspection().thumbnails());
        when(store.metadataHash(entry)).thenReturn("a".repeat(64));
        doAnswer(invocation -> { acknowledged.set(true); return null; }).when(store).acknowledge(anyString(), anyLong(), anyString(), anyString());
        var fail = new AtomicBoolean(true);
        var paths = new ArrayList<String>();
        var bodies = new ArrayList<String>();
        var authorizations = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/gateway/", exchange -> {
            synchronized (paths) {
                paths.add(exchange.getRequestURI().getPath());
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            boolean reject = exchange.getRequestURI().getPath().endsWith("file-thumbnails") && fail.getAndSet(false);
            byte[] response = (reject ? "{}" : "{\"success\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reject ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var properties = new CloudGatewayProperties();
            properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var credential = credential();
            var limiter = new CloudBandwidthLimiter(131072, System.nanoTime());
            try (var sync = new CloudFileMetadataSync(properties, source, store)) {
                sync.synchronize(credential, limiter, () -> true);
            }
            verify(store, never()).acknowledge(anyString(), anyLong(), anyString(), anyString());
            try (var restarted = new CloudFileMetadataSync(properties, source, store)) {
                restarted.synchronize(credential, limiter, () -> true);
                restarted.synchronize(credential, limiter, () -> true);
            }
            assertTrue(acknowledged.get());
            assertEquals(List.of("/api/v1/gateway/file-metadata", "/api/v1/gateway/file-thumbnails",
                    "/api/v1/gateway/file-metadata", "/api/v1/gateway/file-thumbnails"), paths);
            assertTrue(bodies.get(0).contains("\"metadata\""));
            assertTrue(bodies.get(1).contains("\"localPrinterId\":\"1\""));
            assertTrue(bodies.get(1).contains(entry.inspection().metadata().thumbnails().getFirst().sha256()));
            assertTrue(authorizations.stream().allMatch(value -> "Bearer isolated-fixture".equals(value)));
        } finally { server.stop(0); }
    }

    @Test void missingCurrentIdentityAndStoppedSessionsPerformNoHttpRequests() throws Exception {
        var entry = entry();
        var source = mock(CloudSnapshotSource.class);
        var store = mock(GCodeMetadataStore.class);
        when(source.sourceInstanceId()).thenReturn(UUID.randomUUID().toString());
        when(store.pending(anyString(), anyLong(), anyString(), eq(true))).thenReturn(List.of(entry));
        when(store.pending(anyString(), anyLong(), anyString(), eq(false))).thenReturn(List.of());
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.close(); });
        server.start();
        try {
            var properties = new CloudGatewayProperties();
            properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            try (var sync = new CloudFileMetadataSync(properties, source, store)) {
                sync.synchronize(credential(), new CloudBandwidthLimiter(131072, System.nanoTime()), () -> true);
                sync.synchronize(credential(), new CloudBandwidthLimiter(131072, System.nanoTime()), () -> false);
            }
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    private CloudWire.Credential credential() {
        return new CloudWire.Credential(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, "isolated-fixture", System.currentTimeMillis() + 60000, "fixture-client", "fixture-user", "fixture-topic");
    }

    private GCodeMetadataStore.Entry entry() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        String base64 = Base64.getEncoder().encodeToString(output.toByteArray());
        byte[] bytes = ("; generated by OrcaSlicer 2.3.0\n; thumbnail begin 2x2 " + base64.length() + "\n; "
                + base64 + "\n; thumbnail end\nG1 Z0.2\n").getBytes(StandardCharsets.UTF_8);
        var inspection = new GCodeFileInspector().inspect(new ByteArrayInputStream(bytes), "fixture.gcode", bytes.length, "SERVER_UPLOAD");
        return new GCodeMetadataStore.Entry("execution:fixture", 1L, UUID.randomUUID().toString(), "device-1", "fixture.gcode", inspection);
    }
}
