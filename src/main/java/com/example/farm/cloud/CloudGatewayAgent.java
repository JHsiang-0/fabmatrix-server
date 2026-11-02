package com.example.farm.cloud;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "farm.cloud.enabled", havingValue = "true")
public class CloudGatewayAgent implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(CloudGatewayAgent.class);
    private final CloudGatewayProperties properties;
    private final CloudSnapshotSource source;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final String bootId = UUID.randomUUID().toString();
    private volatile boolean running;
    private volatile CloudWire.Ack receivedAck;
    private volatile Status status = new Status("STARTING", false, null, null, null, false);
    private ScheduledExecutorService executor;
    private CloudWire.Credential credential;
    private HttpClient http;
    private URI api;
    private MqttClient mqtt;
    private javax.net.ssl.SSLContext tls;
    private long epoch;
    private long sequence;
    private boolean registered;
    private boolean terminal;
    private Pending pending;
    private long lastCapture;
    private long lastSnapshot;
    private String lastSnapshotBody;
    private long nextAttempt;
    private int retrySeconds = 1;
    private Long lastAckAt;
    private long lastWarning;

    public CloudGatewayAgent(CloudGatewayProperties properties, CloudSnapshotSource source) {
        this.properties = properties;
        this.source = source;
    }

    public record Status(String state, boolean connected, String gatewayId, Long lastAckAt, String errorCode, boolean pending) { }
    private record Pending(CloudWire.Packet packet, String raw, long lastSentAt) { }
    public Status status() { return status; }

    @Override public void start() {
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("farm-cloud-gateway").factory());
        executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    private void tick() {
        if (!running || terminal || System.currentTimeMillis() < nextAttempt) { return; }
        try {
            prepare();
            if (credential.expiresAt() <= System.currentTimeMillis()) { throw new RemoteFailure("CREDENTIAL_EXPIRED"); }
            if (mqtt == null || !mqtt.isConnected()) { connect(); }
            if (!registered) {
                var session = post("/api/v1/gateway/session", credential.credential(),
                        new CloudWire.Boot(credential.gatewayId(), credential.bindingVersion(), bootId), CloudWire.Session.class);
                epoch = session.epoch();
                sequence = Math.max(sequence, session.lastSequence());
                registered = true;
            }
            handleAck();
            if (terminal) { return; }
            long now = System.currentTimeMillis();
            if (pending != null && pending.packet().observedAt().toEpochMilli() < now - 60000) { clearPending(); lastSnapshotBody = null; }
            if (pending == null && now - lastCapture >= 5000) {
                var printers = properties.isObservationV2Enabled() ? source.captureV2() : source.capture();
                lastCapture = now;
                var payload = new CloudWire.Payload("1.0.0", printers.size(), printers);
                String body = json.writeValueAsString(payload);
                boolean snapshot = !body.equals(lastSnapshotBody) || now - lastSnapshot >= 30000;
                if (snapshot || lastAckAt == null || now - lastAckAt >= 15000) {
                    var packet = new CloudWire.Packet(properties.isObservationV2Enabled() ? 2 : 1, UUID.randomUUID().toString(), snapshot ? "SNAPSHOT" : "HEARTBEAT",
                            credential.gatewayId(), credential.bindingVersion(), bootId, epoch, ++sequence, Instant.now(),
                            snapshot ? payload : new CloudWire.Payload("1.0.0", printers.size(), null));
                    String raw = json.writeValueAsString(packet);
                    if (raw.getBytes(StandardCharsets.UTF_8).length > 65536) { throw new RemoteFailure("SNAPSHOT_TOO_LARGE"); }
                    CloudAgentFiles.write(pendingFile(), raw);
                    pending = new Pending(packet, raw, 0);
                    if (snapshot) { lastSnapshot = now; lastSnapshotBody = body; }
                }
            }
            if (pending != null && now - pending.lastSentAt() >= 5000) {
                String topic = credential.topicPrefix() + "up/" + pending.packet().type().toLowerCase(java.util.Locale.ROOT);
                mqtt.publish(topic, pending.raw().getBytes(StandardCharsets.UTF_8), 1, false);
                pending = new Pending(pending.packet(), pending.raw(), now);
            }
            retrySeconds = 1;
            status = new Status(lastAckAt == null || now - lastAckAt > 45000 ? "SYNCHRONIZING" : "CONNECTED", mqtt.isConnected(), credential.gatewayId(),
                    lastAckAt, status.errorCode(), pending != null);
        } catch (Exception error) {
            registered = false;
            String code = error instanceof RemoteFailure failure ? failure.getMessage() : error.getClass().getSimpleName();
            if ("STALE_BOOT".equals(code)) { terminal = true; }
            status = new Status(terminal ? "SUPERSEDED" : "RETRYING", false, credential == null ? null : credential.gatewayId(),
                    lastAckAt, code, pending != null);
            nextAttempt = System.currentTimeMillis() + retrySeconds * 1000L + java.util.concurrent.ThreadLocalRandom.current().nextInt(500);
            retrySeconds = Math.min(30, retrySeconds * 2);
            if (System.currentTimeMillis() - lastWarning > 30000) {
                LOG.warn("Cloud gateway retry scheduled: {}", code);
                lastWarning = System.currentTimeMillis();
            }
            closeMqtt();
        }
    }

    private void prepare() throws Exception {
        if (http == null) {
            api = CloudTls.apiUri(properties.getApiUrl());
            URI broker = URI.create(properties.getMqttUrl());
            if (!"ssl".equals(broker.getScheme()) || broker.getHost() == null || broker.getUserInfo() != null
                    || broker.getQuery() != null || (broker.getPath() != null && !broker.getPath().isEmpty())) {
                throw new RemoteFailure("MQTT_TLS_REQUIRED");
            }
            tls = CloudTls.trusted(properties.getCaCertificate());
            http = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(5)).build();
        }
        if (credential == null) {
            Path file = Path.of(properties.getCredentialFile());
            if (Files.exists(file)) {
                var loaded = json.readValue(CloudAgentFiles.read(file, 8192), CloudWire.Credential.class);
                validate(loaded);
                credential = loaded;
            } else {
                // Verify protected storage can be written before consuming a single-use ticket.
                CloudAgentFiles.verifyWritable(file);
                CloudAgentFiles.verifyWritable(pendingFile());
                if (properties.getBindingTokenFile().isBlank()) { throw new RemoteFailure("BINDING_REQUIRED"); }
                String token = CloudAgentFiles.read(Path.of(properties.getBindingTokenFile()), 128).trim();
                if (!token.matches("fmc_b_[A-Za-z0-9_-]{43}")) { throw new RemoteFailure("BINDING_INVALID"); }
                var bound = post("/api/v1/gateways/bind", null, Map.of("bindingToken", token), CloudWire.Credential.class);
                validate(bound);
                CloudAgentFiles.write(file, json.writeValueAsString(bound));
                credential = bound;
            }
            validate(credential);
            // A process restart creates a new boot/epoch and captures current local state instead of replaying an old snapshot.
            Files.deleteIfExists(pendingFile());
        }
    }

    private void validate(CloudWire.Credential value) {
        UUID.fromString(value.gatewayId());
        String prefix = "fabmatrix/v1/gateways/" + value.gatewayId() + "/bindings/" + value.bindingVersion() + "/";
        if (value.bindingVersion() < 1 || value.credential() == null || !value.credential().matches("fmc_g_[A-Za-z0-9_-]{43}")
                || !value.gatewayId().equals(value.mqttUsername()) || !prefix.equals(value.topicPrefix())
                || !(value.gatewayId() + ".v" + value.bindingVersion()).equals(value.mqttClientId())) {
            throw new IllegalArgumentException("Cloud credential metadata is invalid");
        }
    }

    private <T> T post(String path, String token, Object body, Class<T> type) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192) { throw new RemoteFailure("CLOUD_RESPONSE_TOO_LARGE"); }
            if (response.statusCode() != 200) {
                if (response.statusCode() == 409 && new String(bytes, StandardCharsets.UTF_8).contains("STALE_BOOT")) {
                    throw new RemoteFailure("STALE_BOOT");
                }
                throw new RemoteFailure("CLOUD_HTTP_" + response.statusCode());
            }
            return json.readValue(bytes, type);
        }
    }

    private void connect() throws Exception {
        closeMqtt();
        mqtt = new MqttClient(properties.getMqttUrl(), credential.mqttClientId(), new MemoryPersistence());
        mqtt.setTimeToWait(5000);
        mqtt.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) { }
            public void deliveryComplete(IMqttDeliveryToken token) { }
            public void messageArrived(String topic, MqttMessage message) {
                if (!topic.equals(credential.topicPrefix() + "down/state-ack") || message.getPayload().length > 4096) { return; }
                try { receivedAck = json.readValue(message.getPayload(), CloudWire.Ack.class); }
                catch (IOException ignored) { }
            }
        });
        var options = new MqttConnectOptions();
        options.setUserName(credential.mqttUsername());
        options.setPassword(credential.credential().toCharArray());
        options.setSocketFactory(tls.getSocketFactory());
        options.setHttpsHostnameVerificationEnabled(true);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(15);
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        mqtt.connect(options);
        mqtt.subscribe(credential.topicPrefix() + "down/state-ack", 1);
        registered = false;
        lastSnapshotBody = null;
    }

    private void handleAck() throws IOException {
        var ack = receivedAck;
        receivedAck = null;
        if (pending == null || ack == null || !pending.packet().messageId().equals(ack.messageId())
                || pending.packet().epoch() != ack.epoch() || pending.packet().sequence() != ack.sequence()) { return; }
        LOG.debug("Cloud gateway state receipt result={} code={}", ack.result(), ack.code());
        if ("REJECTED".equals(ack.result())) {
            if ("STALE_BOOT".equals(ack.code())) { terminal = true; closeMqtt(); }
            status = new Status(terminal ? "SUPERSEDED" : "RETRYING", !terminal, credential.gatewayId(), lastAckAt, ack.code(), false);
            lastSnapshotBody = null;
        } else if (java.util.Set.of("ACCEPTED", "DUPLICATE", "STALE").contains(ack.result())) {
            lastAckAt = System.currentTimeMillis();
            status = new Status("CONNECTED", true, credential.gatewayId(), lastAckAt, null, false);
        } else { return; }
        clearPending();
    }

    private Path pendingFile() { return Path.of(properties.getStateDirectory()).resolve("cloud-snapshot-pending.json"); }
    private void clearPending() throws IOException { Files.deleteIfExists(pendingFile()); pending = null; }
    private void closeMqtt() {
        if (mqtt == null) { return; }
        try { if (mqtt.isConnected()) { mqtt.disconnectForcibly(1000, 1000); } } catch (Exception ignored) { }
        try { mqtt.close(true); } catch (Exception ignored) { }
        mqtt = null;
    }

    @Override public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(6, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        closeMqtt();
        if (http != null) { http.close(); }
        status = new Status("STOPPED", false, credential == null ? null : credential.gatewayId(), lastAckAt, null, pending != null);
    }
    @Override public boolean isRunning() { return running; }
    private static final class RemoteFailure extends IOException { RemoteFailure(String code) { super(code); } }
}
