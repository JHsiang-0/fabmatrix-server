package com.example.farm.protocol.file;

import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.model.GCodeFileMetadata;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Low-frequency, read-only file access. It uses a separate client from status polling and controls. */
@Component
public class PrinterFileMetadataReader {
    public static final int MAX_FALLBACK_BYTES = 64 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final int MAX_THUMBNAIL_PARTS = 512;
    private static final long READ_DEADLINE_NANOS = Duration.ofSeconds(30).toNanos();
    private final GCodeFileInspector inspector;
    private final BoundedDeviceFileHttp http;
    private final ObjectMapper mapper;

    @Autowired
    public PrinterFileMetadataReader(GCodeFileInspector inspector) {
        this(inspector, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build());
    }

    PrinterFileMetadataReader(GCodeFileInspector inspector, HttpClient client) {
        this.inspector = inspector;
        this.http = new BoundedDeviceFileHttp(client);
        this.mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                .maxStringLength(GCodeThumbnailCodec.MAX_INPUT_BYTES * 2).build());
    }

    public GCodeFileInspection read(PrinterEndpoint endpoint, String filename) throws IOException {
        return read(endpoint, filename, false);
    }

    public GCodeFileInspection read(PrinterEndpoint endpoint, String filename, boolean includeLayerIndex) throws IOException {
        if (endpoint == null || endpoint.protocolType() == null) throw new IOException("Missing device file endpoint");
        String file = safeFilePath(filename, endpoint.protocolType());
        URI base = baseUri(endpoint);
        long deadline = System.nanoTime() + READ_DEADLINE_NANOS;
        return endpoint.protocolType() == PrinterProtocolType.KLIPPER
                ? moonraker(endpoint, base, file, includeLayerIndex, deadline)
                : rrf(endpoint, base, file, includeLayerIndex, deadline);
    }

    private GCodeFileInspection moonraker(PrinterEndpoint endpoint, URI base, String file,
                                          boolean includeLayers, long deadline) throws IOException {
        Map<String, String> headers = endpoint.apiKey() == null || endpoint.apiKey().isBlank()
                ? Map.of() : Map.of("X-Api-Key", endpoint.apiKey());
        URI metadataUri = uri(base, "/server/files/metadata", "filename", file);
        JsonNode info;
        try {
            info = json(metadataUri, headers, deadline).path("result");
            if (!info.isObject()) throw new IOException("Invalid Moonraker file metadata");
        } catch (BoundedDeviceFileHttp.StatusException unsupported) {
            if (unsupported.status() != 404) throw unsupported;
            return fallback(uri(base, "/server/files/gcodes/" + file), headers, file, -1, deadline);
        }
        Long size = integer(info.get("size"), Long.MAX_VALUE);
        if (size == null) throw new IOException("Missing Moonraker file size");
        requireRequestedFile(info, "filename", file, PrinterProtocolType.KLIPPER);
        List<GCodeFileInspection.ThumbnailImage> images = new ArrayList<>();
        boolean hasRetryablePreviewFailure = false;
        JsonNode previews = info.path("thumbnails");
        if (previews.isArray()) {
            for (JsonNode preview : candidates(previews)) {
                String relative = text(preview, "relative_path", 512);
                if (relative == null) continue;
                try {
                    String parent = file.contains("/") ? file.substring(0, file.lastIndexOf('/') + 1) : "";
                    String path = safeFilePath(parent + relative, PrinterProtocolType.KLIPPER);
                    byte[] raw = http.get(uri(base, "/server/files/gcodes/" + path), headers,
                            GCodeThumbnailCodec.MAX_INPUT_BYTES, deadline);
                    images.add(checkedImage(raw, preview));
                } catch (IOException invalidPreview) {
                    if (System.nanoTime() >= deadline) throw invalidPreview;
                    hasRetryablePreviewFailure |= retryablePreviewFailure(invalidPreview);
                }
            }
        }
        GCodeFileInspection result = deviceMetadata(endpoint, file, info, images, false);
        result = supplement(result, uri(base, "/server/files/gcodes/" + file), headers, file, size,
                includeLayers, deadline);
        JsonNode after = json(metadataUri, headers, deadline).path("result");
        requireSameVersion(info, after, "size", "modified", "uuid", "filename");
        requirePreviewRecovery(result, hasRetryablePreviewFailure);
        return result;
    }

    private GCodeFileInspection rrf(PrinterEndpoint endpoint, URI base, String file,
                                    boolean includeLayers, long deadline) throws IOException {
        JsonNode connection = json(uri(base, "/rr_connect", "password", endpoint.apiKey() == null ? "" : endpoint.apiKey(),
                "sessionKey", "yes"), Map.of(), deadline);
        requireSuccess(connection);
        JsonNode sessionValue = connection.get("sessionKey");
        String session = sessionValue != null && sessionValue.isIntegralNumber() && sessionValue.canConvertToLong()
                && sessionValue.longValue() >= 0 ? sessionValue.asText() : text(connection, "sessionKey", 256);
        Map<String, String> headers = session == null ? Map.of() : Map.of("X-Session-Key", session);
        try {
            String devicePath = "0:/gcodes/" + file;
            URI metadataUri = uri(base, "/rr_fileinfo", "name", devicePath);
            JsonNode info = json(metadataUri, headers, deadline);
            requireSuccess(info);
            Long size = integer(info.get("size"), Long.MAX_VALUE);
            if (size == null) throw new IOException("Missing RRF file size");
            requireRequestedFile(info, "fileName", file, PrinterProtocolType.RRF);
            List<GCodeFileInspection.ThumbnailImage> images = new ArrayList<>();
            boolean hasRetryablePreviewFailure = false;
            JsonNode previews = info.path("thumbnails");
            if (previews.isArray()) {
                for (JsonNode preview : candidates(previews)) {
                    Long offset = integer(preview.get("offset"), Long.MAX_VALUE);
                    if (offset == null) continue;
                    try {
                        images.add(checkedImage(rrfThumbnail(base, headers, devicePath, offset, deadline), preview));
                    } catch (IOException invalidPreview) {
                        if (System.nanoTime() >= deadline) throw invalidPreview;
                        hasRetryablePreviewFailure |= retryablePreviewFailure(invalidPreview);
                    }
                }
            }
            GCodeFileInspection result = deviceMetadata(endpoint, file, info, images, true);
            result = supplement(result, uri(base, "/rr_download", "name", devicePath), headers, file, size,
                    includeLayers, deadline);
            JsonNode after = json(metadataUri, headers, deadline);
            requireSuccess(after);
            requireSameVersion(info, after, "size", "lastModified", "fileName");
            requirePreviewRecovery(result, hasRetryablePreviewFailure);
            return result;
        } finally {
            // Older RRF may share a session by source IP. Release only an independently issued key.
            if (session != null) {
                try {
                    http.get(uri(base, "/rr_disconnect"), headers, MAX_METADATA_BYTES,
                            Math.max(deadline, System.nanoTime() + Duration.ofSeconds(2).toNanos()));
                } catch (IOException ignored) { }
            }
        }
    }

    private static boolean retryablePreviewFailure(IOException failure) {
        if (failure instanceof BoundedDeviceFileHttp.TransientReadException) return true;
        if (failure instanceof BoundedDeviceFileHttp.StatusException response) {
            int status = response.status();
            return status == 408 || status == 425 || status == 429
                    || (status >= 500 && status != 501 && status != 505);
        }
        return false;
    }

    private static void requirePreviewRecovery(GCodeFileInspection result, boolean retryableFailure) throws IOException {
        if (retryableFailure && result.thumbnails().isEmpty()) {
            throw new IOException("Device thumbnail collection is temporarily unavailable; retry required");
        }
    }

    private byte[] rrfThumbnail(URI base, Map<String, String> headers, String path, long offset,
                                 long deadline) throws IOException {
        StringBuilder encoded = new StringBuilder();
        Set<Long> offsets = new HashSet<>();
        for (int part = 0; part < MAX_THUMBNAIL_PARTS; part++) {
            if (!offsets.add(offset)) throw new IOException("Repeated RRF thumbnail offset");
            JsonNode response = json(uri(base, "/rr_thumbnail", "name", path, "offset", Long.toString(offset)), headers, deadline);
            requireSuccess(response);
            Long returnedOffset = integer(response.get("offset"), Long.MAX_VALUE);
            if (returnedOffset != null && returnedOffset != offset) throw new IOException("RRF thumbnail offset mismatch");
            String data = response.path("data").isTextual() ? response.path("data").textValue() : null;
            if (data == null || !data.matches("[A-Za-z0-9+/=]*")
                    || encoded.length() + data.length() > GCodeThumbnailCodec.MAX_INPUT_BYTES * 4L / 3 + 4) {
                throw new IOException("Invalid RRF thumbnail data");
            }
            encoded.append(data);
            Long next = integer(response.get("next"), Long.MAX_VALUE);
            if (next == null) throw new IOException("Missing RRF thumbnail continuation");
            if (next == 0) {
                try { return Base64.getDecoder().decode(encoded.toString()); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid RRF thumbnail encoding"); }
            }
            if (next <= offset) throw new IOException("Invalid RRF thumbnail continuation");
            offset = next;
        }
        throw new IOException("RRF thumbnail exceeds the part limit");
    }

    private GCodeFileInspection supplement(GCodeFileInspection device, URI download, Map<String, String> headers,
                                            String file, long size, boolean includeLayers, long deadline) throws IOException {
        var metadata = device.metadata();
        if (size > MAX_FALLBACK_BYTES || (!includeLayers && metadata.estimatedPrintSeconds() != null
                && metadata.totalLayers() != null && !device.thumbnails().isEmpty())) return device;
        try {
            GCodeFileInspection parsed = fallback(download, headers, file, size, deadline);
            return merge(device, parsed);
        } catch (IOException unavailable) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) throw unavailable;
            return device;
        }
    }

    private GCodeFileInspection fallback(URI download, Map<String, String> headers, String file,
                                          long size, long deadline) throws IOException {
        if (size > MAX_FALLBACK_BYTES) throw new IOException("G-code fallback exceeds the read limit");
        byte[] content = http.get(download, headers, MAX_FALLBACK_BYTES, deadline);
        return inspector.inspect(new ByteArrayInputStream(content), file, size, "GCODE_FALLBACK");
    }

    private GCodeFileInspection deviceMetadata(PrinterEndpoint endpoint, String file, JsonNode info,
                                               List<GCodeFileInspection.ThumbnailImage> images, boolean rrf) {
        String name = text(info, rrf ? "generatedBy" : "slicer", 80);
        String version = text(info, "slicer_version", 80);
        if (rrf && name != null && name.contains(" ")) {
            String[] generated = name.split("\\s+", 3);
            name = generated[0];
            version = generated.length > 1 ? generated[1] : null;
        }
        String identity = endpoint.protocolType() + "|" + endpoint.printerId() + "|" + file + "|"
                + info.path("size") + "|" + info.path(rrf ? "lastModified" : "modified") + "|" + info.path("uuid");
        List<GCodeFileInspection.ThumbnailImage> selected = selectImages(images);
        var metadata = new GCodeFileMetadata(1, GCodeThumbnailCodec.sha256(identity.getBytes(StandardCharsets.UTF_8)),
                GCodeFileInspector.safeBasename(file), integer(info.get("size"), Long.MAX_VALUE), null,
                rrf ? "RRF" : "MOONRAKER", Instant.now(), name, version,
                seconds(info.get(rrf ? "printTime" : "estimated_time")),
                positiveCount(info.get(rrf ? "numLayers" : "layer_count")),
                positive(info.get(rrf ? "layerHeight" : "layer_height"), 10),
                positive(info.get(rrf ? "firstLayerHeight" : "first_layer_height"), 10),
                positive(info.get(rrf ? "nozzleDiameter" : "nozzle_diameter"), 10),
                positive(info.get(rrf ? "height" : "object_height"), 100_000),
                temperature(info.get("nozzle_temperature")), temperature(info.get("first_layer_extr_temp")),
                temperature(info.get("bed_temperature")), temperature(info.get("first_layer_bed_temp")),
                deviceFilaments(info, rrf), selected.stream().map(GCodeFileInspection.ThumbnailImage::descriptor).toList());
        return new GCodeFileInspection(metadata, selected, List.of());
    }

    private static List<GCodeFileMetadata.Filament> deviceFilaments(JsonNode info, boolean rrf) {
        List<String> materials = strings(info.get("filament_type"));
        List<String> colors = strings(info.get("filament_colors"));
        JsonNode lengths = info.path(rrf ? "filament" : "filament_lengths");
        JsonNode weights = info.path("filament_weights");
        int count = Math.max(Math.max(materials.size(), colors.size()),
                Math.max(lengths.isArray() ? lengths.size() : 0, weights.isArray() ? weights.size() : 0));
        if (count == 0 && (info.path("filament_total").isNumber() || info.path("filament_weight_total").isNumber())) count = 1;
        List<GCodeFileMetadata.Filament> filaments = new ArrayList<>();
        for (int i = 0; i < Math.min(count, 8); i++) {
            String color = i < colors.size() && colors.get(i).matches("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?") ? colors.get(i) : null;
            BigDecimal length = nonNegative(lengths.path(i), 1_000_000_000);
            BigDecimal weight = nonNegative(weights.path(i), 1_000_000);
            if (count == 1) {
                if (length == null) length = nonNegative(info.get("filament_total"), 1_000_000_000);
                if (weight == null) weight = nonNegative(info.get("filament_weight_total"), 1_000_000);
            }
            filaments.add(new GCodeFileMetadata.Filament("tool-" + i, i < materials.size() ? materials.get(i) : null,
                    color, length, weight));
        }
        return filaments;
    }

    private static GCodeFileInspection merge(GCodeFileInspection device, GCodeFileInspection parsed) {
        var d = device.metadata();
        var p = parsed.metadata();
        List<GCodeFileInspection.ThumbnailImage> selected = selectImages(
                Stream.concat(device.thumbnails().stream(), parsed.thumbnails().stream()).toList());
        var metadata = new GCodeFileMetadata(1, p.fileId(), d.fileName(), p.sizeBytes(), p.sha256(), "GCODE_FALLBACK",
                p.parsedAt(), first(d.slicerName(), p.slicerName()), first(d.slicerVersion(), p.slicerVersion()),
                first(d.estimatedPrintSeconds(), p.estimatedPrintSeconds()), first(d.totalLayers(), p.totalLayers()),
                first(d.layerHeightMm(), p.layerHeightMm()), first(d.firstLayerHeightMm(), p.firstLayerHeightMm()),
                first(d.nozzleDiameterMm(), p.nozzleDiameterMm()), first(d.objectHeightMm(), p.objectHeightMm()),
                first(d.nozzleTemperatureC(), p.nozzleTemperatureC()), first(d.firstLayerNozzleTemperatureC(), p.firstLayerNozzleTemperatureC()),
                first(d.bedTemperatureC(), p.bedTemperatureC()), first(d.firstLayerBedTemperatureC(), p.firstLayerBedTemperatureC()),
                d.filaments().isEmpty() ? p.filaments() : d.filaments(),
                selected.stream().map(GCodeFileInspection.ThumbnailImage::descriptor).toList());
        return new GCodeFileInspection(metadata, selected, parsed.layers());
    }

    private static <T> T first(T value, T fallback) { return value == null ? fallback : value; }
    private static List<GCodeFileInspection.ThumbnailImage> selectImages(List<GCodeFileInspection.ThumbnailImage> images) {
        Map<String, GCodeFileInspection.ThumbnailImage> unique = new LinkedHashMap<>();
        images.forEach(image -> unique.putIfAbsent(image.descriptor().sha256(), image));
        List<GCodeFileInspection.ThumbnailImage> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparingInt(image -> image.descriptor().width() * image.descriptor().height()));
        return sorted.size() <= 2 ? List.copyOf(sorted) : List.of(sorted.getFirst(), sorted.getLast());
    }
    private static List<JsonNode> candidates(JsonNode images) {
        List<JsonNode> result = new ArrayList<>();
        images.elements().forEachRemaining(image -> { if (result.size() < 8 && image.isObject()) result.add(image); });
        result.sort(Comparator.comparingLong(image -> image.path("width").asLong(0) * image.path("height").asLong(0)));
        return result.size() <= 2 ? result : List.of(result.getFirst(), result.getLast());
    }
    private static GCodeFileInspection.ThumbnailImage checkedImage(byte[] raw, JsonNode declared) throws IOException {
        var image = GCodeThumbnailCodec.normalize(raw);
        if (image.descriptor().width() != declared.path("width").asInt(-1)
                || image.descriptor().height() != declared.path("height").asInt(-1)) throw new IOException("Thumbnail dimensions changed");
        return image;
    }
    private JsonNode json(URI uri, Map<String, String> headers, long deadline) throws IOException {
        try {
            JsonNode result = mapper.readTree(http.get(uri, headers, MAX_METADATA_BYTES, deadline));
            if (result == null || !result.isObject()) throw new IOException("Invalid device file response");
            return result;
        }
        catch (JacksonException invalid) { throw new IOException("Invalid device file response"); }
    }
    private static void requireSuccess(JsonNode result) throws IOException {
        if (result == null || !result.isObject() || (result.has("err") && result.path("err").asInt(-1) != 0)) {
            throw new IOException("Device rejected file read");
        }
    }
    private static void requireSameVersion(JsonNode before, JsonNode after, String... fields) throws IOException {
        if (!after.isObject()) throw new IOException("Device file changed during collection");
        for (String field : fields) {
            if (!before.path(field).equals(after.path(field))) throw new IOException("Device file changed during collection");
        }
    }
    private static void requireRequestedFile(JsonNode info, String key, String requested,
                                             PrinterProtocolType protocol) throws IOException {
        JsonNode returned = info.get(key);
        if (returned != null && !returned.isNull()
                && (!returned.isTextual() || !safeFilePath(returned.textValue(), protocol).equals(requested))) {
            throw new IOException("Device returned a different file");
        }
    }
    private static Long integer(JsonNode value, long maximum) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 0 && value.longValue() <= maximum ? value.longValue() : null;
    }
    private static Long positiveCount(JsonNode value) {
        Long number = integer(value, 1_000_000);
        return number != null && number > 0 ? number : null;
    }
    private static Long seconds(JsonNode value) {
        BigDecimal number = nonNegative(value, 315_576_000);
        return number == null ? null : number.setScale(0, RoundingMode.CEILING).longValue();
    }
    private static BigDecimal positive(JsonNode value, long maximum) {
        BigDecimal number = nonNegative(value, maximum);
        return number != null && number.signum() > 0 ? number : null;
    }
    private static BigDecimal nonNegative(JsonNode value, long maximum) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) return null;
        BigDecimal number = value.decimalValue();
        return number.signum() >= 0 && number.compareTo(BigDecimal.valueOf(maximum)) <= 0 ? number : null;
    }
    private static Integer temperature(JsonNode value) {
        Long number = integer(value, 500);
        return number == null ? null : number.intValue();
    }
    private static String text(JsonNode node, String key, int maximum) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum || value.textValue().matches("(?s).*\\p{Cntrl}.*")) return null;
        return value.textValue();
    }
    private static List<String> strings(JsonNode value) {
        if (value == null) return List.of();
        List<String> values = new ArrayList<>();
        if (value.isArray()) value.elements().forEachRemaining(item -> { if (values.size() < 8 && item.isTextual()) values.add(item.textValue()); });
        else if (value.isTextual()) values.addAll(List.of(value.textValue().split("[;,]", 8)));
        return values.stream().map(String::trim).filter(item -> !item.isBlank() && item.length() <= 80
                && !item.matches("(?s).*\\p{Cntrl}.*")).toList();
    }
    private static String safeFilePath(String filename, PrinterProtocolType type) throws IOException {
        if (filename == null || filename.length() > 512 || filename.isBlank() || filename.indexOf('\\') >= 0
                || filename.matches("(?s).*\\p{Cntrl}.*")) throw new IOException("Invalid device file path");
        String path = filename;
        if (type == PrinterProtocolType.RRF && path.startsWith("0:/gcodes/")) path = path.substring(10);
        else if (type == PrinterProtocolType.RRF && path.startsWith("/gcodes/")) path = path.substring(8);
        if (path.startsWith("/") || path.indexOf(':') >= 0) {
            throw new IOException("Device file path must stay in the G-code root");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) throw new IOException("Invalid device file path");
        }
        return path;
    }
    private static URI baseUri(PrinterEndpoint endpoint) throws IOException {
        try {
            URI candidate = URI.create("http://" + endpoint.ipAddress());
            if (candidate.getHost() == null || candidate.getUserInfo() != null || candidate.getQuery() != null
                    || candidate.getFragment() != null || !candidate.getPath().isEmpty()) throw new IllegalArgumentException();
            int port = candidate.getPort() >= 0 ? candidate.getPort()
                    : endpoint.protocolType() == PrinterProtocolType.KLIPPER ? 7125 : 80;
            return new URI("http", null, candidate.getHost(), port, null, null, null);
        } catch (IllegalArgumentException | URISyntaxException invalid) { throw new IOException("Invalid device file endpoint"); }
    }
    private static URI uri(URI base, String path, String... parameters) throws IOException {
        try {
            String raw = new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, null, null).toASCIIString();
            StringBuilder query = new StringBuilder();
            for (int i = 0; i < parameters.length; i += 2) {
                if (!query.isEmpty()) query.append('&');
                query.append(URLEncoder.encode(parameters[i], StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(parameters[i + 1], StandardCharsets.UTF_8));
            }
            return URI.create(raw + (query.isEmpty() ? "" : "?" + query));
        } catch (IllegalArgumentException | URISyntaxException invalid) { throw new IOException("Invalid device file request"); }
    }
}
