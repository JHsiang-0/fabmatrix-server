package com.example.farm.cloud;

import com.example.farm.service.CurrentExecutionPauseService;
import com.example.farm.service.HeaterTargetControlService;
import com.example.farm.service.RemoteProductionControlService;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Separate bounded C3 worker. Neither MQTT callback nor telemetry publisher executes device actions. */
public final class CloudRemoteControlWorker implements AutoCloseable {
    private final CloudGatewayProperties properties;
    private final CloudSnapshotSource source;
    private final CloudRemoteControlPolicyStore policy;
    private final CloudRemoteCommandInbox inbox;
    private final CurrentExecutionPauseService pause;
    private final HeaterTargetControlService heaters;
    private final RemoteProductionControlService production;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ArrayBlockingQueue<CloudControlWire.Notice> notices = new ArrayBlockingQueue<>(32);
    private volatile Context current;
    private volatile boolean running;
    private volatile long nextPolicy;
    private volatile long nextAttempt;
    private volatile Context policySynced;
    private volatile Boolean temperatureProtocolSupported;
    private final Map<String, Long> receiptRetryAt = new HashMap<>();
    private ScheduledExecutorService executor;
    private HttpClient http;
    private URI api;
    private record Context(CloudWire.Credential credential, CloudControlWire.Session session, BooleanSupplier active) { }

    public CloudRemoteControlWorker(CloudGatewayProperties properties, CloudSnapshotSource source,
            CloudRemoteControlPolicyStore policy, CloudRemoteCommandInbox inbox, CurrentExecutionPauseService pause,
            HeaterTargetControlService heaters) {
        this(properties, source, policy, inbox, pause, heaters, null);
    }
    public CloudRemoteControlWorker(CloudGatewayProperties properties, CloudSnapshotSource source,
            CloudRemoteControlPolicyStore policy, CloudRemoteCommandInbox inbox, CurrentExecutionPauseService pause,
            HeaterTargetControlService heaters, RemoteProductionControlService production) {
        this.properties = properties; this.source = source; this.policy = policy; this.inbox = inbox; this.pause = pause;
        this.heaters = Objects.requireNonNull(heaters);
        this.production = production;
        json.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        json.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        json.coercionConfigFor(LogicalType.Float).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    }

    public void start() {
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("farm-cloud-control").factory());
        executor.scheduleWithFixedDelay(this::tick, 0, 250, TimeUnit.MILLISECONDS);
    }

    public void connected(CloudWire.Credential credential, String bootId, long epoch, BooleanSupplier active) {
        disconnect();
        current = new Context(credential, new CloudControlWire.Session(credential.gatewayId(),
                credential.bindingVersion(), bootId, epoch, source.sourceInstanceId()), active);
        nextPolicy = 0;
        nextAttempt = 0;
        temperatureProtocolSupported = null;
    }
    public void disconnect() { current = null; policySynced = null; notices.clear(); }
    public void policyChanged() { policySynced = null; temperatureProtocolSupported = null; nextPolicy = 0; nextAttempt = 0; }

    /** Bound parsing and enqueue only; called on the Paho receive thread. */
    public void notice(byte[] bytes, boolean retained) {
        if (retained || bytes == null || bytes.length > 4096 || !running) return;
        Context context = current;
        if (!active(context) || System.currentTimeMillis() < nextAttempt) return;
        try {
            var notice = json.readValue(bytes, CloudControlWire.Notice.class);
            if (notice.schemaVersion() == 1 && "COMMAND_AVAILABLE".equals(notice.type())
                    && validUuid(notice.commandId()) && same(context.session(), notice.gatewayId(),
                        notice.bindingVersion(), notice.bootId(), notice.epoch())) {
                notices.offer(notice);
            }
        } catch (Exception ignored) { /* Untrusted notifications cannot affect observation processing. */ }
    }

    private void tick() {
        Context context = current;
        if (!active(context) || System.currentTimeMillis() < nextAttempt) return;
        try {
            prepareHttp();
            inbox.initialize();
            if (System.currentTimeMillis() >= nextPolicy) {
                var view = policy.view(context.credential());
                var s = context.session();
                CloudControlWire.Success result;
                if (Boolean.FALSE.equals(temperatureProtocolSupported)) {
                    result = legacyPolicy(context, view);
                } else {
                    try {
                        result = post(context, "/api/v1/gateway/control-policy",
                                new CloudControlWire.Policy(s.gatewayId(), s.bindingVersion(), s.bootId(), s.epoch(),
                                        s.sourceInstanceId(), view.remoteEnabled(), view.revision(), view.remoteEnabled(), 1,
                                        view.remoteEnabled(), 1, 3, CloudControlWire.ACTIONS), CloudControlWire.Success.class);
                        if (result.success()) temperatureProtocolSupported = true;
                    } catch (RemoteHttp unsupported) {
                        if (unsupported.status != 400) throw unsupported;
                        temperatureProtocolSupported = false;
                        result = legacyPolicy(context, view);
                    }
                }
                if (!result.success()) return;
                if (!active(context)) return;
                policySynced = context;
                nextPolicy = System.currentTimeMillis() + 10000;
            }
            if (policySynced != context) return;
            var notice = notices.poll();
            if (notice != null && same(context.session(), notice.gatewayId(), notice.bindingVersion(),
                    notice.bootId(), notice.epoch())) {
                try { process(context, notice); }
                catch (RemoteHttp failure) {
                    // Policy support is already established. A rejected claim belongs to this notice only.
                    if (failure.status < 400 || failure.status >= 500 || failure.status == 401 || failure.status == 429) throw failure;
                }
            }
            // A receipt cannot starve a new command. Retry at most one eligible receipt per tick.
            for (var entry : inbox.pendingResults(context.session())) {
                if (!active(context)) return;
                if (System.currentTimeMillis() >= receiptRetryAt.getOrDefault(entry.command().commandId(), 0L)) {
                    deliverTerminal(context, entry); break;
                }
            }
        } catch (RemoteHttp unavailable) {
            // An older Cloud has no C3 endpoints; it must not stop V1/V2/V3 observation traffic.
            nextAttempt = System.currentTimeMillis() + (unavailable.status == 404 || unavailable.status == 405 ? 60000 : 5000);
            nextPolicy = nextAttempt;
            policySynced = null;
            notices.clear();
        } catch (Exception unavailable) {
            nextAttempt = System.currentTimeMillis() + 5000;
            nextPolicy = nextAttempt;
            policySynced = null;
        }
    }

    private void process(Context context, CloudControlWire.Notice notice) throws Exception {
        var session = context.session();
        var command = post(context, "/api/v1/gateway/commands/claim", new CloudControlWire.Claim(session.gatewayId(),
                session.bindingVersion(), session.bootId(), session.epoch(), notice.commandId()), CloudControlWire.Command.class);
        validateShape(command);
        if (!notice.commandId().equals(command.commandId()) || !same(session, command.gatewayId(),
                command.bindingVersion(), command.bootId(), command.epoch())
                || !session.sourceInstanceId().equals(command.sourceInstanceId())) return;
        var entry = inbox.accept(command);
        if (!"RECEIVED".equals(entry.status())) {
            if ("EXECUTING".equals(entry.status())) {
                inbox.complete(command, new CloudControlWire.Outcome("UNKNOWN", "RESULT_UNKNOWN"));
                entry = inbox.find(command.commandId());
            }
            deliverTerminal(context, entry);
            return;
        }
        CloudControlWire.Outcome outcome;
        try {
            checkpoint(context, command);
            CurrentExecutionPauseService.Checkpoint check = () -> {
                checkpoint(context, command);
                if (command.schemaVersion() == 3) {
                    if (production == null) reject("REJECTED", "CONTROL_UNSUPPORTED");
                    production.validateContext(command);
                }
            };
            CurrentExecutionPauseService.BeforeAction before = () -> {
                checkpoint(context, command);
                if (!inbox.executing(command.commandId())) throw new IllegalStateException("Command already attempted");
                if (!sendResult(context, command.commandId(), "EXECUTING", "OK")) {
                    throw new IllegalStateException("Execution receipt not accepted");
                }
                checkpoint(context, command);
            };
            outcome = switch (command.action()) {
                case "SET_HEATER_TARGET" -> heaters.apply(command, check, before);
                case "PAUSE" -> pause.pause(command, check, before);
                default -> production == null ? new CloudControlWire.Outcome("REJECTED", "CONTROL_UNSUPPORTED") : production.apply(command, check, before);
            };
        } catch (CurrentExecutionPauseService.Rejected rejected) {
            outcome = new CloudControlWire.Outcome(rejected.getStatus(), rejected.getCode());
        }
        inbox.complete(command, outcome);
        deliverTerminal(context, new CloudRemoteCommandInbox.Entry(command, outcome.status(), outcome.code(), false));
    }

    private void deliverTerminal(Context context, CloudRemoteCommandInbox.Entry entry) {
        String id = entry.command().commandId();
        try {
            if (sendResult(context, id, entry.status(), entry.code())) {
                inbox.reported(id); receiptRetryAt.remove(id);
            } else receiptRetryAt.put(id, System.currentTimeMillis() + 5000);
        } catch (RemoteHttp failure) {
            if (failure.status >= 400 && failure.status < 500 && failure.status != 401 && failure.status != 429) {
                inbox.deliveryRejected(entry); receiptRetryAt.remove(id);
            } else receiptRetryAt.put(id, System.currentTimeMillis() + 5000);
        } catch (Exception unavailable) { receiptRetryAt.put(id, System.currentTimeMillis() + 5000); }
    }

    private void checkpoint(Context context, CloudControlWire.Command command) {
        if (!active(context)) reject("REJECTED", "STALE_SESSION");
        if (!CloudControlWire.ACTIONS.contains(command.action())) reject("REJECTED", "UNSUPPORTED_ACTION");
        CloudRemoteControlPolicyStore.View currentPolicy;
        try { currentPolicy = policy.view(context.credential()); }
        catch (RuntimeException unreadable) { reject("REJECTED", "LOCAL_STORAGE_FAILED"); return; }
        if (!currentPolicy.remoteEnabled()) reject("REJECTED", "CONTROL_DISABLED");
        if (!Boolean.TRUE.equals(temperatureProtocolSupported)) reject("REJECTED", "CONTROL_UNSUPPORTED");
        if (currentPolicy.revision() != command.policyRevision()
                || !policy.allows(context.session(), command.policyRevision(), command.action())) reject("REJECTED", "POLICY_CHANGED");
        long now = System.currentTimeMillis();
        if (command.issuedAt() <= 0 || command.expiresAt() <= 0
                || command.expiresAt() <= now || command.issuedAt() > now + 5000
                || command.expiresAt() <= command.issuedAt()
                || command.expiresAt() - command.issuedAt() > 30000) reject("EXPIRED", "COMMAND_EXPIRED");
        if (!source.sourceInstanceId().equals(command.sourceInstanceId())) reject("REJECTED", "IDENTITY_MISMATCH");
    }

    private void validateShape(CloudControlWire.Command command) {
        boolean temperature = command != null && "SET_HEATER_TARGET".equals(command.action());
        boolean modern = command != null && command.schemaVersion() == 3;
        if (command == null || !CloudControlWire.ACTIONS.contains(command.action())
                || !modern && command.schemaVersion() != (temperature ? 2 : 1) || !validUuid(command.commandId())
                || !validUuid(command.gatewayId()) || !validUuid(command.bootId())
                || !validUuid(command.sourceInstanceId()) || !validUuid(command.deviceUid())
                || ((!modern && !temperature) || !"IDLE".equals(command.expectedOperationState())) && !validUuid(command.expectedExecutionId())
                || !validUuid(command.actorUserId())
                || command.localPrinterId() == null || !command.localPrinterId().matches("[1-9][0-9]{0,18}")
                || command.actorUsername() == null || command.actorUsername().isBlank()
                || command.actorUsername().length() > 100 || command.actorUsername().chars().anyMatch(Character::isISOControl)
                || command.expectedFileName() != null && (command.expectedFileName().length() > 512
                    || command.expectedFileName().chars().anyMatch(Character::isISOControl))
                || command.expectedFileSizeBytes() != null && command.expectedFileSizeBytes() < 0
                || command.expectedFilePositionBytes() != null && command.expectedFilePositionBytes() < 0
                || command.bindingVersion() < 1 || command.epoch() < 1 || command.policyRevision() < 1) {
            throw new IllegalArgumentException("Invalid control contract");
        }
        if (modern && (!validUuid(command.expectedDeviceBootId()) || command.expectedDescriptorRevision() == null
                || command.expectedDescriptorRevision() < 1 || !Set.of("IDLE", "PRINTING", "PAUSED").contains(command.expectedOperationState())
                || "IDLE".equals(command.expectedOperationState()) && command.expectedExecutionId() != null)) throw new IllegalArgumentException("Invalid control context");
        if (temperature && (command.heaterId() == null || command.heaterId().isBlank() || command.heaterId().length() > 64
                || command.heaterId().chars().anyMatch(Character::isISOControl) || command.targetC() == null
                || command.expectedTargetC() == null || command.expectedTargetC().signum() < 0
                || command.expectedTargetC().compareTo(BigDecimal.valueOf(1000)) > 0
                || command.expectedTargetC().stripTrailingZeros().scale() > 15
                || !validUuid(command.expectedDeviceBootId()) || command.expectedDescriptorRevision() == null || command.expectedDescriptorRevision() < 1
                || !Set.of("IDLE", "PRINTING", "PAUSED").contains(command.expectedOperationState())
                || "IDLE".equals(command.expectedOperationState()) && command.expectedExecutionId() != null)) throw new IllegalArgumentException("Invalid heater contract");
        if (Set.of("SET_FAN_SPEED", "SET_SPEED_FACTOR", "SET_FLOW_FACTOR").contains(command.action())) {
            boolean fan = "SET_FAN_SPEED".equals(command.action());
            if (!modern || command.targetPercent() == null || command.targetPercent() < (fan ? 0 : 10) || command.targetPercent() > (fan ? 100 : 200)
                    || !decimal(command.expectedPercent()) || command.expectedPercent().compareTo(BigDecimal.valueOf(fan ? 100 : 1000)) > 0
                    || fan && !identifier(command.fanId()) || "SET_FLOW_FACTOR".equals(command.action()) && !identifier(command.toolId())
                    || !fan && "IDLE".equals(command.expectedOperationState())) throw new IllegalArgumentException("Invalid runtime control");
        }
        if (Set.of("RESUME", "CANCEL").contains(command.action()) && (!modern || command.expectedExecutionId() == null
                || "IDLE".equals(command.expectedOperationState()) || "RESUME".equals(command.action()) && !"PAUSED".equals(command.expectedOperationState()))) throw new IllegalArgumentException("Invalid execution control");
        if ("HEATERS_OFF".equals(command.action())) {
            var expected = command.expectedHeaterTargets();
            if (!modern || !"IDLE".equals(command.expectedOperationState()) || expected == null || expected.isEmpty() || expected.size() > 16
                    || expected.stream().anyMatch(value -> value == null || !identifier(value.heaterId()) || !decimal(value.expectedTargetC()))
                    || expected.stream().map(CloudControlWire.HeaterTarget::heaterId).distinct().count() != expected.size()) throw new IllegalArgumentException("Invalid heaters-off control");
        }
    }

    private boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9._:-]{1,64}"); }
    private boolean decimal(BigDecimal value) { return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(1000)) <= 0 && value.stripTrailingZeros().scale() <= 15; }

    private CloudControlWire.Success legacyPolicy(Context context, CloudRemoteControlPolicyStore.View view) throws Exception {
        var s = context.session();
        return post(context, "/api/v1/gateway/control-policy", Map.of("gatewayId", s.gatewayId(), "bindingVersion", s.bindingVersion(),
                "bootId", s.bootId(), "epoch", s.epoch(), "sourceInstanceId", s.sourceInstanceId(),
                "enabled", false, "policyRevision", view.revision()), CloudControlWire.Success.class);
    }

    private boolean sendResult(Context context, String id, String status, String code) throws Exception {
        if (!active(context)) return false;
        var s = context.session();
        return post(context, "/api/v1/gateway/commands/result", new CloudControlWire.Result(s.gatewayId(),
                s.bindingVersion(), s.bootId(), s.epoch(), id, status, code), CloudControlWire.Success.class).success();
    }
    private boolean active(Context context) {
        return running && context != null && current == context && context.active().getAsBoolean()
                && context.credential().expiresAt() > System.currentTimeMillis();
    }
    private boolean same(CloudControlWire.Session expected, String gateway, long binding, String boot, long epoch) {
        return Objects.equals(expected.gatewayId(), gateway) && expected.bindingVersion() == binding
                && Objects.equals(expected.bootId(), boot) && expected.epoch() == epoch;
    }
    private boolean validUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private void reject(String status, String code) { throw new CurrentExecutionPauseService.Rejected(status, code); }

    private void prepareHttp() throws Exception {
        if (http != null) return;
        api = CloudTls.apiUri(properties.getApiUrl());
        var tls = properties.getCaCertificatePem().isBlank() ? CloudTls.trusted(properties.getCaCertificate())
                : CloudTls.trustedPem(properties.getCaCertificatePem());
        http = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }
    private <T> T post(Context context, String path, Object body, Class<T> type) throws Exception {
        if (!active(context)) throw new RemoteHttp(409);
        var request = HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + context.credential().credential())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192 || response.statusCode() != 200) throw new RemoteHttp(response.statusCode());
            if (type == CloudControlWire.Command.class) validateRawCommand(json.readTree(bytes));
            return json.readValue(bytes, type);
        }
    }

    private void validateRawCommand(com.fasterxml.jackson.databind.JsonNode command) {
        String action = command.path("action").asText();
        var allowed = new java.util.HashSet<String>();
        if ("SET_HEATER_TARGET".equals(action)) allowed.addAll(Set.of("heaterId", "targetC", "expectedTargetC"));
        if ("SET_FAN_SPEED".equals(action)) allowed.addAll(Set.of("fanId", "targetPercent", "expectedPercent"));
        if ("SET_SPEED_FACTOR".equals(action)) allowed.addAll(Set.of("targetPercent", "expectedPercent"));
        if ("SET_FLOW_FACTOR".equals(action)) allowed.addAll(Set.of("toolId", "targetPercent", "expectedPercent"));
        if ("HEATERS_OFF".equals(action)) allowed.add("expectedHeaterTargets");
        for (String field : Set.of("heaterId", "targetC", "expectedTargetC", "fanId", "targetPercent", "expectedPercent", "toolId", "expectedHeaterTargets")) {
            if (command.has(field) && !allowed.contains(field)) throw new IllegalArgumentException("Unexpected action parameter");
        }
        if (command.path("schemaVersion").asInt() == 1) for (String field : Set.of("expectedDeviceBootId", "expectedDescriptorRevision", "expectedOperationState")) {
            if (command.has(field)) throw new IllegalArgumentException("Unexpected legacy parameter");
        }
    }
    private static final class RemoteHttp extends Exception {
        final int status;
        RemoteHttp(int status) { super("CONTROL_HTTP_" + status); this.status = status; }
    }
    @Override public void close() {
        running = false; disconnect();
        if (executor != null) executor.shutdownNow();
        if (http != null) http.shutdownNow();
    }
}
