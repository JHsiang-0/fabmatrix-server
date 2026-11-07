package com.example.farm.cloud;

import com.example.farm.mapper.GCodeMetadataStore;
import com.example.farm.protocol.model.GCodeFileMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

/** Low-priority, durable HTTP synchronization independent from the MQTT status loop. */
final class CloudFileMetadataSync implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CloudFileMetadataSync.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);
    private final CloudGatewayProperties properties;
    private final CloudSnapshotSource source;
    private final GCodeMetadataStore store;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("farm-cloud-file-sync").factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<String, Long> retries = new HashMap<>();
    private volatile boolean closed;
    private volatile HttpClient client;
    private long lastWarning;
    private String activeCursor = "";
    private String historyCursor = "";

    CloudFileMetadataSync(CloudGatewayProperties properties, CloudSnapshotSource source, GCodeMetadataStore store) {
        this.properties = properties;
        this.source = source;
        this.store = store;
    }

    void submit(CloudWire.Credential credential, CloudBandwidthLimiter bandwidth, BooleanSupplier active) {
        if (closed || !busy.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { synchronize(credential, bandwidth, active); }
                catch (Exception failure) { warn(); }
                finally { busy.set(false); }
            });
        } catch (RejectedExecutionException stopping) { busy.set(false); }
    }

    void synchronize(CloudWire.Credential credential, CloudBandwidthLimiter bandwidth,
                             BooleanSupplier active) throws Exception {
        if (!active.getAsBoolean() || closed) return;
        var activeEntries = store.pending(credential.gatewayId(), credential.bindingVersion(), activeCursor, true);
        var historyEntries = store.pending(credential.gatewayId(), credential.bindingVersion(), historyCursor, false);
        if (activeEntries.isEmpty()) activeCursor = "";
        if (historyEntries.isEmpty()) historyCursor = "";
        if (activeEntries.isEmpty() && historyEntries.isEmpty()) return;
        var tls = properties.getCaCertificatePem().isBlank() ? CloudTls.trusted(properties.getCaCertificate())
                : CloudTls.trustedPem(properties.getCaCertificatePem());
        try (var http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            client = http;
            int used = batch(http, credential, bandwidth, active, activeEntries, true, 4);
            batch(http, credential, bandwidth, active, historyEntries, false, 8 - used);
        } finally { client = null; }
    }

    private int batch(HttpClient http, CloudWire.Credential credential, CloudBandwidthLimiter bandwidth,
                      BooleanSupplier active, List<GCodeMetadataStore.Entry> entries,
                      boolean current, int limit) throws Exception {
        String cursor = current ? activeCursor : historyCursor;
        int attempts = 0;
        boolean exhausted = true;
        for (var entry : entries) {
            if (closed || !active.getAsBoolean() || Thread.currentThread().isInterrupted()) return attempts;
            if (attempts >= limit) { exhausted = false; break; }
            cursor = entry.cacheKey();
            if (System.currentTimeMillis() < retries.getOrDefault(entry.cacheKey(), 0L)
                    || !source.matchesCurrentIdentity(entry.printerId(), entry.deviceUid())) continue;
            attempts++;
            try {
                transfer(http, credential, bandwidth, active, entry);
                retries.remove(entry.cacheKey());
            } catch (Exception failure) {
                if (retries.size() >= 512) retries.clear();
                retries.put(entry.cacheKey(), System.currentTimeMillis() + 60000);
                warn();
            }
        }
        if (exhausted && entries.size() < 64) cursor = "";
        if (current) activeCursor = cursor;
        else historyCursor = cursor;
        return attempts;
    }

    private void transfer(HttpClient http, CloudWire.Credential credential, CloudBandwidthLimiter bandwidth,
                          BooleanSupplier active, GCodeMetadataStore.Entry entry) throws Exception {
        var metadataBody = new MetadataUpload(credential.gatewayId(), credential.bindingVersion(), source.sourceInstanceId(),
                entry.deviceUid(), entry.printerId().toString(), entry.executionUid(), entry.inspection().metadata());
        post(http, "/api/v1/gateway/file-metadata", credential, metadataBody, bandwidth, active);
        var images = store.images(entry);
        if (images.size() != entry.inspection().metadata().thumbnails().size()) throw new IOException("IMAGE_CACHE_INCOMPLETE");
        for (var image : images) {
            var imageBody = new ThumbnailUpload(credential.gatewayId(), credential.bindingVersion(),
                    entry.printerId().toString(), entry.executionUid(), image.descriptor().sha256(),
                    Base64.getEncoder().encodeToString(image.png()));
            post(http, "/api/v1/gateway/file-thumbnails", credential, imageBody, bandwidth, active);
        }
        if (closed || !active.getAsBoolean()) throw new InterruptedIOException("FILE_SYNC_STOPPED");
        store.acknowledge(credential.gatewayId(), credential.bindingVersion(), entry.executionUid(), store.metadataHash(entry));
    }

    private record MetadataUpload(String gatewayId, long bindingVersion, String sourceInstanceId, String deviceUid,
                                  String localPrinterId, String executionId,
                                  GCodeFileMetadata metadata) { }
    private record ThumbnailUpload(String gatewayId, long bindingVersion, String localPrinterId,
                                   String executionId, String sha256, String data) {
        @Override public String toString() { return "ThumbnailUpload[redacted]"; }
    }

    private void post(HttpClient http, String path, CloudWire.Credential credential, Object body,
                      CloudBandwidthLimiter bandwidth, BooleanSupplier active) throws Exception {
        byte[] bytes = json.writeValueAsBytes(body);
        if (bytes.length > 750000) throw new IOException("FILE_UPLOAD_TOO_LARGE");
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new BackgroundInput(bytes, bandwidth,
                () -> !closed && active.getAsBoolean(), deadline));
        var request = HttpRequest.newBuilder(URI.create(CloudTls.apiUri(properties.getApiUrl()) + path))
                .timeout(REQUEST_TIMEOUT).header("Authorization", "Bearer " + credential.credential())
                .header("Content-Type", "application/json").POST(publisher).build();
        var response = http.send(request, smallResponse());
        if (response.statusCode() != 200 || !json.readTree(response.body()).path("success").asBoolean(false)) {
            throw new IOException("FILE_UPLOAD_RETRY");
        }
    }

    private static HttpResponse.BodyHandler<byte[]> smallResponse() {
        return response -> {
            var bytes = new ByteArrayOutputStream();
            return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk ->
                    chunk.ifPresent(value -> {
                        if (bytes.size() + value.length > 8192) throw new CompletionException(new IOException("FILE_RESPONSE_TOO_LARGE"));
                        bytes.writeBytes(value);
                    })), ignored -> bytes.toByteArray());
        };
    }

    static final class BackgroundInput extends InputStream {
        private final ByteArrayInputStream input;
        private final CloudBandwidthLimiter bandwidth;
        private final BooleanSupplier active;
        private final long deadline;
        private volatile boolean closed;

        BackgroundInput(byte[] bytes, CloudBandwidthLimiter bandwidth, BooleanSupplier active) {
            this(bytes, bandwidth, active, System.nanoTime() + REQUEST_TIMEOUT.toNanos());
        }

        BackgroundInput(byte[] bytes, CloudBandwidthLimiter bandwidth, BooleanSupplier active, long deadline) {
            input = new ByteArrayInputStream(bytes);
            this.bandwidth = bandwidth;
            this.active = active;
            this.deadline = deadline;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            ensureActive();
            int chunk = Math.min(Math.min(length, 1024), input.available());
            if (chunk == 0) return -1;
            while (!bandwidth.tryAcquireBackground(chunk, System.nanoTime())) {
                ensureActive();
                try { Thread.sleep(50); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new InterruptedIOException("FILE_SYNC_STOPPED"); }
            }
            ensureActive();
            return input.read(bytes, offset, chunk);
        }

        private void ensureActive() throws InterruptedIOException {
            if (closed || !active.getAsBoolean() || Thread.currentThread().isInterrupted()
                    || System.nanoTime() - deadline >= 0) throw new InterruptedIOException("FILE_SYNC_STOPPED");
        }

        @Override public void close() { closed = true; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }
    }

    private void warn() {
        long now = System.currentTimeMillis();
        if (now - lastWarning >= 60000) {
            LOG.warn("Cloud file metadata synchronization will retry");
            lastWarning = now;
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        var current = client;
        if (current != null) current.shutdownNow();
    }
}
