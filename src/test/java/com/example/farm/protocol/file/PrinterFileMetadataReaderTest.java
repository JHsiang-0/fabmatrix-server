package com.example.farm.protocol.file;

import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.stream.Stream;

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

    @ParameterizedTest
    @MethodSource("temperatureValues")
    void readsExactWholeDegreeTemperaturesWithoutTruncatingOrRounding(String value, Integer expected) throws Exception {
        serveTemperatureMetadata("\"nozzle_temperature\":" + value + ",\"first_layer_extr_temp\":" + value
                + ",\"bed_temperature\":" + value + ",\"first_layer_bed_temp\":" + value, "100");
        for (var protocol : List.of(PrinterProtocolType.KLIPPER, PrinterProtocolType.RRF)) {
            var metadata = reader.read(endpoint(protocol), "test.gcode").metadata();
            assertThat(metadata.nozzleTemperatureC()).isEqualTo(expected);
            assertThat(metadata.firstLayerNozzleTemperatureC()).isEqualTo(expected);
            assertThat(metadata.bedTemperatureC()).isEqualTo(expected);
            assertThat(metadata.firstLayerBedTemperatureC()).isEqualTo(expected);
        }
        assertThat(requests).noneMatch(request -> request.contains("rr_download") || request.endsWith("/gcodes/test.gcode"));
    }

    static Stream<Arguments> temperatureValues() {
        return Stream.of(Arguments.of("245", 245), Arguments.of("245.0", 245), Arguments.of("2.45e2", 245),
                Arguments.of("0.0", 0), Arguments.of("500.0", 500), Arguments.of("245.5", null),
                Arguments.of("245.000000000000000001", null), Arguments.of("500.000000000000000001", null),
                Arguments.of("-1.0", null), Arguments.of("501.0", null), Arguments.of("\"245\"", null),
                Arguments.of("true", null), Arguments.of("null", null), Arguments.of("[245]", null));
    }

    @ParameterizedTest
    @MethodSource("filamentTemperatures")
    void usesOnlyAnUnambiguousCommonFilamentTemperature(String values, Integer expected) throws Exception {
        serveTemperatureMetadata("\"filament_temps\":" + values, "100");
        var metadata = reader.read(endpoint(PrinterProtocolType.KLIPPER), "test.gcode").metadata();
        assertThat(metadata.nozzleTemperatureC()).isEqualTo(expected);
        assertThat(metadata.firstLayerNozzleTemperatureC()).isNull();
        assertThat(metadata.bedTemperatureC()).isNull();
        assertThat(metadata.firstLayerBedTemperatureC()).isNull();
    }

    static Stream<Arguments> filamentTemperatures() {
        return Stream.of(Arguments.of("[245]", 245), Arguments.of("[245.0,245]", 245), Arguments.of("[0]", 0),
                Arguments.of("[245,250]", null), Arguments.of("[0,245]", null), Arguments.of("[245,null]", null),
                Arguments.of("[245,\"245\"]", null), Arguments.of("[245,true]", null),
                Arguments.of("[245.000000000000000001]", null), Arguments.of("[]", null),
                Arguments.of("[245,245,245,245,245,245,245,245]", 245),
                Arguments.of("[245,245,245,245,245,245,245,245,245]", null), Arguments.of("245", null));
    }

    @Test
    void retainsAnExplicitNozzleTemperatureAheadOfDifferentFilamentTemperatures() throws Exception {
        serveTemperatureMetadata("\"nozzle_temperature\":230.0,\"filament_temps\":[245,250]", "100");
        assertThat(reader.read(endpoint(PrinterProtocolType.KLIPPER), "test.gcode").metadata().nozzleTemperatureC()).isEqualTo(230);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1700000000.125", "-0.0", "1.7e9"})
    void preservesExistingFileIdentityEncodingWhenReadingPreciseTemperatures(String modified) throws Exception {
        serveTemperatureMetadata("\"first_layer_extr_temp\":245.000000000000000001", modified);
        var old = json.readTree("{\"size\":" + (PrinterFileMetadataReader.MAX_FALLBACK_BYTES + 1L)
                + ",\"modified\":" + modified + "}");
        String oldIdentity = "KLIPPER|1|test.gcode|" + old.path("size") + "|" + old.path("modified") + "|" + old.path("uuid");
        var metadata = reader.read(endpoint(PrinterProtocolType.KLIPPER), "test.gcode").metadata();
        assertThat(metadata.fileId()).isEqualTo(GCodeThumbnailCodec.sha256(oldIdentity.getBytes(StandardCharsets.UTF_8)));
        assertThat(metadata.firstLayerNozzleTemperatureC()).isNull();
    }

    private void serveTemperatureMetadata(String fields, String modified) {
        String info = "{\"err\":0,\"size\":" + (PrinterFileMetadataReader.MAX_FALLBACK_BYTES + 1L)
                + ",\"modified\":" + modified + ",\"lastModified\":\"version-1\",\"estimated_time\":300,\"printTime\":300,"
                + "\"layer_count\":25,\"numLayers\":25," + fields + "}";
        handler = exchange -> switch (exchange.getRequestURI().getPath()) {
            case "/server/files/metadata" -> new Reply(200, "application/json", ("{\"result\":" + info + "}").getBytes(StandardCharsets.UTF_8));
            case "/rr_fileinfo" -> new Reply(200, "application/json", info.getBytes(StandardCharsets.UTF_8));
            case "/rr_connect" -> reply(Map.of("err", 0));
            default -> new Reply(404, "text/plain", new byte[0]);
        };
    }

    @ParameterizedTest
    @EnumSource(value = PrinterProtocolType.class, names = {"KLIPPER", "RRF"})
    void retriesDeclaredThumbnailAfterTemporaryFailureAndRecovers(PrinterProtocolType protocol) throws Exception {
        byte[] png = GCodeFileInspectorTest.png(2, 2);
        AtomicInteger reads = new AtomicInteger();
        serveThumbnailFixture(protocol, true, exchange -> reads.getAndIncrement() == 0
                ? new Reply(503, "text/plain", new byte[0]) : thumbnailReply(protocol, png), null);

        assertThatThrownBy(() -> reader.read(endpoint(protocol), "test.gcode"))
                .isInstanceOf(IOException.class).hasMessageContaining("retry required")
                .hasMessageNotContaining("fixture-key");
        var recovered = reader.read(endpoint(protocol), "test.gcode");
        assertThat(recovered.thumbnails()).hasSize(1);
        assertThat(recovered.metadata().thumbnails()).containsExactly(recovered.thumbnails().getFirst().descriptor());
        assertThat(reads).hasValue(2);
        if (protocol == PrinterProtocolType.RRF) {
            assertThat(requests.stream().filter(request -> request.equals("GET /rr_disconnect"))).hasSize(2);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PrinterProtocolType.class, names = {"KLIPPER", "RRF"})
    void retainsMetadataWithoutDeclaredPreviewsEvenWhenFallbackUnavailable(PrinterProtocolType protocol) throws Exception {
        serveThumbnailFixture(protocol, false, exchange -> {
            throw new AssertionError("Undeclared thumbnail must not be requested");
        }, null);
        var result = reader.read(endpoint(protocol), "test.gcode");
        assertThat(result.metadata().estimatedPrintSeconds()).isEqualTo(300);
        assertThat(result.metadata().totalLayers()).isEqualTo(25);
        assertThat(result.thumbnails()).isEmpty();
        assertThat(result.metadata().thumbnails()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PrinterProtocolType.class, names = {"KLIPPER", "RRF"})
    void retainsMetadataForUnsupportedOrInvalidPreviewWithoutRepeatedRetries(PrinterProtocolType protocol) throws Exception {
        for (Reply preview : List.of(new Reply(404, "text/plain", new byte[0]),
                new Reply(501, "text/plain", new byte[0]), thumbnailReply(protocol, new byte[]{1, 2, 3}))) {
            serveThumbnailFixture(protocol, true, exchange -> preview, null);
            var result = reader.read(endpoint(protocol), "test.gcode");
            assertThat(result.metadata().estimatedPrintSeconds()).isEqualTo(300);
            assertThat(result.thumbnails()).isEmpty();
            assertThat(result.metadata().thumbnails()).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(value = PrinterProtocolType.class, names = {"KLIPPER", "RRF"})
    void acceptsFallbackPreviewAfterTemporaryDeviceThumbnailFailure(PrinterProtocolType protocol) throws Exception {
        byte[] png = GCodeFileInspectorTest.png(2, 2);
        String encoded = Base64.getEncoder().encodeToString(png);
        byte[] gcode = ("; thumbnail begin 2x2 " + encoded.length() + "\n; " + encoded
                + "\n; thumbnail end\nG1 X10\n").getBytes(StandardCharsets.UTF_8);
        serveThumbnailFixture(protocol, true, exchange -> new Reply(503, "text/plain", new byte[0]), gcode);
        var result = reader.read(endpoint(protocol), "test.gcode");
        assertThat(result.metadata().source()).isEqualTo("GCODE_FALLBACK");
        assertThat(result.thumbnails()).hasSize(1);
        assertThat(result.metadata().thumbnails()).containsExactly(result.thumbnails().getFirst().descriptor());
    }

    private void serveThumbnailFixture(PrinterProtocolType protocol, boolean declared,
                                       Function<HttpExchange, Reply> preview, byte[] fallback) {
        List<Map<String, Object>> thumbnails = declared ? List.of(protocol == PrinterProtocolType.KLIPPER
                ? Map.of("width", 2, "height", 2, "relative_path", ".thumbs/test.png")
                : Map.of("width", 2, "height", 2, "offset", 100)) : List.of();
        int size = fallback == null ? 12345 : fallback.length;
        handler = exchange -> switch (exchange.getRequestURI().getPath()) {
            case "/server/files/metadata" -> reply(Map.of("result", Map.of("size", size, "modified", 100,
                    "estimated_time", 300, "layer_count", 25, "thumbnails", thumbnails)));
            case "/rr_connect" -> reply(Map.of("err", 0, "sessionKey", "fixture-session"));
            case "/rr_fileinfo" -> reply(Map.of("err", 0, "size", size, "lastModified", "revision-1",
                    "printTime", 300, "numLayers", 25, "thumbnails", thumbnails));
            case "/server/files/gcodes/.thumbs/test.png", "/rr_thumbnail" -> preview.apply(exchange);
            case "/server/files/gcodes/test.gcode", "/rr_download" -> fallback == null
                    ? new Reply(503, "text/plain", new byte[0]) : new Reply(200, "application/octet-stream", fallback);
            case "/rr_disconnect" -> reply(Map.of("err", 0));
            default -> new Reply(404, "text/plain", new byte[0]);
        };
    }

    private Reply thumbnailReply(PrinterProtocolType protocol, byte[] image) {
        return protocol == PrinterProtocolType.KLIPPER ? new Reply(200, "image/png", image)
                : reply(Map.of("err", 0, "offset", 100, "next", 0, "data", Base64.getEncoder().encodeToString(image)));
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
