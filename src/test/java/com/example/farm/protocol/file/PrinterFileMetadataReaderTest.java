package com.example.farm.protocol.file;

import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterFileMetadataReaderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private volatile Function<HttpExchange, Reply> handler;
    private PrinterFileMetadataReader reader;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            try {
                Reply reply = handler.apply(exchange);
                exchange.getResponseHeaders().add("Content-Type", reply.contentType());
                exchange.sendResponseHeaders(reply.status(), reply.body().length);
                exchange.getResponseBody().write(reply.body());
            } catch (RuntimeException invalid) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        reader = new PrinterFileMetadataReader(new GCodeFileInspector());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void readsMoonrakerMetadataAndPrivateThumbnailWithEncodedFilenameAndVersionCheck() throws Exception {
        byte[] png = GCodeFileInspectorTest.png(10, 12);
        AtomicInteger metadataReads = new AtomicInteger();
        handler = exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Api-Key")).isEqualTo("fixture-key");
            return switch (exchange.getRequestURI().getPath()) {
                case "/server/files/metadata" -> {
                    metadataReads.incrementAndGet();
                    assertThat(query(exchange, "filename")).isEqualTo("models/part + #1.gcode");
                    yield reply(Map.of("result", Map.of("size", 123456, "modified", 100, "uuid", "test-version",
                            "slicer", "OrcaSlicer", "slicer_version", "2.3.1", "estimated_time", 300,
                            "layer_count", 25, "thumbnails", List.of(Map.of("width", 10, "height", 12,
                                    "relative_path", ".thumbs/part.png")))));
                }
                case "/server/files/gcodes/models/.thumbs/part.png" -> new Reply(200, "image/png", png);
                default -> new Reply(404, "text/plain", new byte[0]);
            };
        };
        var result = reader.read(endpoint(PrinterProtocolType.KLIPPER), "models/part + #1.gcode");
        assertThat(result.metadata().source()).isEqualTo("MOONRAKER");
        assertThat(result.metadata().fileName()).isEqualTo("part + #1.gcode");
        assertThat(result.metadata().estimatedPrintSeconds()).isEqualTo(300);
        assertThat(result.metadata().totalLayers()).isEqualTo(25);
        assertThat(result.metadata().sha256()).isNull();
        assertThat(result.thumbnails()).hasSize(1);
        assertThat(result.metadata().thumbnails()).containsExactly(result.thumbnails().getFirst().descriptor());
        assertThat(metadataReads).hasValue(2);
        assertThat(requests).allMatch(request -> request.startsWith("GET "));
        assertThat(requests).noneMatch(request -> request.endsWith("part + #1.gcode"));
    }

    @Test
    void readsRrfNumericSessionAndPagedQoiPreviewAndReleasesIndependentSession() throws Exception {
        byte[] qoi = GCodeThumbnailCodecTest.qoi(4, 1,
                new byte[]{(byte) 0xFE, 10, 20, 30, (byte) 0xC2});
        String encoded = Base64.getEncoder().encodeToString(qoi);
        handler = exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/rr_connect")) {
                assertThat(exchange.getRequestHeaders().getFirst("X-Session-Key")).isEqualTo("123");
            }
            return switch (path) {
                case "/rr_connect" -> reply(Map.of("err", 0, "sessionKey", 123));
                case "/rr_fileinfo" -> reply(Map.of("err", 0, "size", 1200, "lastModified", "2026-11-01T12:00:00",
                        "generatedBy", "OrcaSlicer 2.3.1", "printTime", 100, "numLayers", 3,
                        "thumbnails", List.of(Map.of("width", 4, "height", 1, "format", "qoi", "offset", 100))));
                case "/rr_thumbnail" -> query(exchange, "offset").equals("100")
                        ? reply(Map.of("err", 0, "offset", 100, "next", 200, "data", encoded.substring(0, 12)))
                        : reply(Map.of("err", 0, "offset", 200, "next", 0, "data", encoded.substring(12)));
                case "/rr_disconnect" -> reply(Map.of("err", 0));
                default -> new Reply(404, "text/plain", new byte[0]);
            };
        };
        var result = reader.read(endpoint(PrinterProtocolType.RRF), "0:/gcodes/test.gcode");
        assertThat(result.metadata().source()).isEqualTo("RRF");
        assertThat(result.metadata().slicerName()).isEqualTo("OrcaSlicer");
        assertThat(result.metadata().totalLayers()).isEqualTo(3);
        assertThat(result.thumbnails()).hasSize(1);
        assertThat(result.thumbnails().getFirst().descriptor().contentType()).isEqualTo("image/png");
        assertThat(requests).containsExactly("GET /rr_connect", "GET /rr_fileinfo", "GET /rr_thumbnail",
                "GET /rr_thumbnail", "GET /rr_fileinfo", "GET /rr_disconnect");
    }

    @Test
    void readsOriginalGcodeForMissingMetadataAndLayerIndexThroughRrfFallback() throws Exception {
        byte[] content = ("; generated by OrcaSlicer 2.3.1\n; total layer number: 2\n; TIME:600\n"
                + "; CHANGE_LAYER\nG1 Z0.2\n; CHANGE_LAYER\nG1 Z0.4\n").getBytes(StandardCharsets.UTF_8);
        handler = exchange -> switch (exchange.getRequestURI().getPath()) {
            case "/rr_connect" -> reply(Map.of("err", 0));
            case "/rr_fileinfo" -> reply(Map.of("err", 0, "size", content.length, "lastModified", "revision-1"));
            case "/rr_download" -> new Reply(200, "application/octet-stream", content);
            default -> new Reply(404, "text/plain", new byte[0]);
        };
        var result = reader.read(endpoint(PrinterProtocolType.RRF), "test.gcode", true);
        assertThat(result.metadata().source()).isEqualTo("GCODE_FALLBACK");
        assertThat(result.metadata().estimatedPrintSeconds()).isEqualTo(600);
        assertThat(result.metadata().totalLayers()).isEqualTo(2);
        assertThat(result.metadata().sha256()).isEqualTo(GCodeThumbnailCodec.sha256(content));
        assertThat(result.currentLayerAt(content.length - 1)).isEqualTo(2);
        assertThat(requests).noneMatch(request -> request.contains("rr_disconnect"));
    }

    @Test
    void rejectsChangedFileVersionAndAvoidsThumbnailPathTraversal() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        handler = exchange -> {
            if (exchange.getRequestURI().getPath().equals("/server/files/metadata")) {
                return reply(Map.of("result", Map.of("size", 123, "modified", reads.incrementAndGet(),
                        "estimated_time", 60, "layer_count", 2,
                        "thumbnails", List.of(Map.of("width", 20, "height", 20, "relative_path", "../../config/private.png")))));
            }
            return new Reply(404, "text/plain", new byte[0]);
        };
        assertThatThrownBy(() -> reader.read(endpoint(PrinterProtocolType.KLIPPER), "test.gcode"))
                .isInstanceOf(IOException.class).hasMessageContaining("changed");
        assertThat(requests).noneMatch(request -> request.contains("private.png") || request.contains("/config/"));
    }

    @Test
    void retainsDeviceMetadataWhenFallbackExceedsLimitAndStopsRepeatedThumbnailOffsets() throws Exception {
        handler = exchange -> switch (exchange.getRequestURI().getPath()) {
            case "/rr_connect" -> reply(Map.of("err", 0, "sessionKey", "independent-key"));
            case "/rr_fileinfo" -> reply(Map.of("err", 0, "size", PrinterFileMetadataReader.MAX_FALLBACK_BYTES + 1L,
                    "printTime", 123, "thumbnails", List.of(Map.of("width", 2, "height", 2, "offset", 50))));
            case "/rr_thumbnail" -> reply(Map.of("err", 0, "offset", 50, "next", 50, "data", "AAAA"));
            case "/rr_disconnect" -> reply(Map.of("err", 0));
            default -> new Reply(404, "text/plain", new byte[0]);
        };
        var result = reader.read(endpoint(PrinterProtocolType.RRF), "test.gcode", true);
        assertThat(result.metadata().estimatedPrintSeconds()).isEqualTo(123);
        assertThat(result.thumbnails()).isEmpty();
        assertThat(result.layers()).isEmpty();
        assertThat(requests.stream().filter(request -> request.endsWith("rr_thumbnail"))).hasSize(1);
        assertThat(requests).noneMatch(request -> request.contains("rr_download"));
    }

    @Test
    void rejectsRootEscapesAndMalformedEndpointsBeforeSendingAnyRequest() {
        List<String> paths = List.of("../sys/config.g", "0:/sys/config.g", "folder/../other.gcode", "a\\test.gcode", "a\ntest.gcode");
        for (String path : paths) {
            assertThatThrownBy(() -> reader.read(endpoint(PrinterProtocolType.RRF), path)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> reader.read(new PrinterEndpoint(1L, "host@127.0.0.1", "fixture-key", PrinterProtocolType.RRF), "test.gcode"))
                .isInstanceOf(IOException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void boundsResponseBodyAndUsesSanitizedCredentialFreeFailures() {
        byte[] huge = new byte[300 * 1024];
        handler = exchange -> new Reply(200, "application/json", huge);
        assertThatThrownBy(() -> reader.read(endpoint(PrinterProtocolType.KLIPPER), "test.gcode"))
                .isInstanceOf(IOException.class).hasMessageNotContaining("fixture-key");
    }

    @Test
    void rejectsMetadataThatIdentifiesAnotherFile() {
        handler = exchange -> reply(Map.of("result", Map.of("size", 123, "filename", "another.gcode")));
        assertThatThrownBy(() -> reader.read(endpoint(PrinterProtocolType.KLIPPER), "test.gcode"))
                .isInstanceOf(IOException.class).hasMessageContaining("different file");
        assertThat(requests).hasSize(1);
    }

    @Test
    void appliesAbsoluteTimeoutAndNeverFollowsRedirects() {
        handler = exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return reply(Map.of("ok", true));
        };
        var transport = new BoundedDeviceFileHttp(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
        long start = System.nanoTime();
        assertThatThrownBy(() -> transport.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"),
                Map.of(), 1024, System.nanoTime() + Duration.ofMillis(100).toNanos())).isInstanceOf(IOException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        handler = exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/outside");
            return new Reply(302, "text/plain", new byte[0]);
        };
        assertThatThrownBy(() -> reader.read(endpoint(PrinterProtocolType.KLIPPER), "test.gcode"))
                .isInstanceOf(IOException.class);
        assertThat(requests).noneMatch(request -> request.contains("outside"));
    }

    private PrinterEndpoint endpoint(PrinterProtocolType type) {
        return new PrinterEndpoint(1L, "127.0.0.1:" + server.getAddress().getPort(), "fixture-key", type);
    }
    private Reply reply(Object value) {
        try { return new Reply(200, "application/json", json.writeValueAsBytes(value)); }
        catch (IOException invalid) { throw new IllegalStateException(invalid); }
    }
    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String entry : raw.split("&")) {
            String[] pair = entry.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                return pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }
    private record Reply(int status, String contentType, byte[] body) { }
}
