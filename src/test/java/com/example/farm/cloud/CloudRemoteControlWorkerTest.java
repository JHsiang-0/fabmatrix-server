package com.example.farm.cloud;

import com.example.farm.service.CurrentExecutionPauseService;
import com.example.farm.service.HeaterTargetControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudRemoteControlWorkerTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    @Test void mqttCallbackOnlyQueuesAndDuplicateCommandNeverRepeatsDeviceAttempt() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.worker.notice(fixture.notice(), false);
            verify(fixture.pause, never()).pause(any(), any(), any());
            assertThat(fixture.paths).isEmpty();
            fixture.tick();
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            verify(fixture.pause, times(1)).pause(any(), any(), any());
            assertThat(fixture.statuses).containsExactly("EXECUTING", "CONFIRMED", "CONFIRMED");
            assertThat(fixture.paths.getFirst()).isEqualTo("/api/v1/gateway/control-policy");
            assertThat(fixture.authorizations).allMatch(value -> value.equals("Bearer isolated-fixture-only"));
        }
    }

    @Test void retainedOversizedOldSessionAndDisconnectedNoticesCannotExecute() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.worker.notice(fixture.notice(), true);
            fixture.worker.notice(new byte[4097], false);
            fixture.worker.notice(json.writeValueAsBytes(new CloudControlWire.Notice(1, "COMMAND_AVAILABLE",
                    fixture.command.commandId(), fixture.command.gatewayId(), 1, UUID.randomUUID().toString(), 1)), false);
            fixture.tick();
            fixture.worker.notice(fixture.notice(), false); fixture.worker.disconnect(); fixture.tick();
            verify(fixture.pause, never()).pause(any(), any(), any());
            assertThat(fixture.paths).containsExactly("/api/v1/gateway/control-policy");
        }
    }

    @Test void disabledPolicyAndExpiredCommandProduceDurableRejectionWithoutDeviceAttempt() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.policy.update(fixture.credential, false, 2);
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            verify(fixture.pause, never()).pause(any(), any(), any());
            assertThat(fixture.codes).containsExactly("CONTROL_DISABLED");
        }
        try (var fixture = new Fixture()) {
            var old = fixture.command;
            fixture.command = new CloudControlWire.Command(old.schemaVersion(), old.commandId(), old.action(), old.gatewayId(),
                    old.bindingVersion(), old.bootId(), old.epoch(), old.sourceInstanceId(), old.deviceUid(), old.localPrinterId(),
                    old.expectedExecutionId(), old.expectedFileName(), old.expectedFileSizeBytes(), old.expectedFilePositionBytes(),
                    old.policyRevision(), old.actorUserId(), old.actorUsername(), System.currentTimeMillis() - 20000,
                    System.currentTimeMillis() - 5000);
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            verify(fixture.pause, never()).pause(any(), any(), any());
            assertThat(fixture.statuses).containsExactly("EXPIRED");
            assertThat(fixture.codes).containsExactly("COMMAND_EXPIRED");
        }
    }

    @Test void cloudWithoutControlEndpointsBacksOffAndCannotAffectTelemetryOrRunPause() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.policyHttpStatus = 404;
            fixture.worker.notice(fixture.notice(), false); fixture.tick(); fixture.tick();
            verify(fixture.pause, never()).pause(any(), any(), any());
            assertThat(fixture.paths).containsExactly("/api/v1/gateway/control-policy");
            assertThat(fixture.statuses).isEmpty();
        }
    }

    @Test void revokedActorAndConflictingTerminalReceiptCannotStarveTheNextPrinterCommand() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.rejectedCommand = fixture.command.commandId();
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            assertThat(fixture.physicalAttempts.get()).isZero();
            verify(fixture.inbox).deliveryRejected(any());
            fixture.nextCommand();
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            assertThat(fixture.physicalAttempts.get()).isEqualTo(1);
            assertThat(fixture.statuses).containsExactly("EXECUTING", "UNKNOWN", "EXECUTING", "CONFIRMED");
        }
    }

    @Test void transientTerminalReceiptFailureDoesNotBlockANewNotification() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.failedReceipt = fixture.command.commandId();
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            fixture.nextCommand();
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            assertThat(fixture.physicalAttempts.get()).isEqualTo(2);
            verify(fixture.pause, times(2)).pause(any(), any(), any());
        }
    }

    @Test void invalidClaimDoesNotDropTheNextValidQueuedNotification() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.invalidClaim = UUID.randomUUID().toString();
            var command = fixture.command;
            fixture.worker.notice(json.writeValueAsBytes(new CloudControlWire.Notice(1, "COMMAND_AVAILABLE",
                    fixture.invalidClaim, command.gatewayId(), command.bindingVersion(), command.bootId(), command.epoch())), false);
            fixture.worker.notice(fixture.notice(), false);
            fixture.tick(); fixture.tick();
            assertThat(fixture.physicalAttempts.get()).isEqualTo(1);
            assertThat(fixture.paths.stream().filter(path -> path.endsWith("control-policy")).count()).isEqualTo(1);
            assertThat(fixture.statuses).containsExactly("EXECUTING", "CONFIRMED");
        }
    }

    @Test void nonPositiveIssuedTimestampCannotOverflowTheThirtySecondDeadline() throws Exception {
        for (long issuedAt : new long[] {0, -1, Long.MIN_VALUE}) {
            try (var fixture = new Fixture()) {
                var old = fixture.command;
                fixture.command = new CloudControlWire.Command(old.schemaVersion(), old.commandId(), old.action(), old.gatewayId(),
                        old.bindingVersion(), old.bootId(), old.epoch(), old.sourceInstanceId(), old.deviceUid(), old.localPrinterId(),
                        old.expectedExecutionId(), old.expectedFileName(), old.expectedFileSizeBytes(), old.expectedFilePositionBytes(),
                        old.policyRevision(), old.actorUserId(), old.actorUsername(), issuedAt, Long.MAX_VALUE);
                fixture.worker.notice(fixture.notice(), false); fixture.tick();
                assertThat(fixture.codes).containsExactly("COMMAND_EXPIRED");
                assertThat(fixture.physicalAttempts.get()).isZero();
            }
        }
    }

    @Test void temperatureUsesSchemaTwoAndItsIndependentGrantWithoutCallingPause() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.temperatureCommand();
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            verify(fixture.heaters).apply(any(), any(), any()); verify(fixture.pause, never()).pause(any(), any(), any());
            assertThat(fixture.statuses).containsExactly("EXECUTING", "CONFIRMED");
            assertThat(fixture.codes).containsExactly("OK", "HEATER_TARGET_CONFIRMED");
        }
    }

    @Test void temperatureWireRejectsFractionalAndStringIntegerCoercion() throws Exception {
        for (String invalid : new String[] {"205.5", "\"205\""}) {
            try (var fixture = new Fixture()) {
                fixture.temperatureCommand();
                fixture.claimBody = json.writeValueAsString(fixture.command).replace("\"targetC\":205", "\"targetC\":" + invalid);
                fixture.worker.notice(fixture.notice(), false); fixture.tick();
                verify(fixture.heaters, never()).apply(any(), any(), any()); assertThat(fixture.statuses).isEmpty();
            }
        }
    }

    @Test void legacyPolicyFallbackIsProbedOnceAndCannotSilentlyAuthorizeTemperature() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.rejectTemperaturePolicy = true;
            fixture.tick();
            ReflectionTestUtils.setField(fixture.worker, "nextPolicy", 0L); fixture.tick();
            assertThat(fixture.temperaturePolicyProbes).isEqualTo(1);
            fixture.temperatureCommand(); fixture.worker.policyChanged();
            fixture.worker.notice(fixture.notice(), false); fixture.tick();
            assertThat(fixture.temperaturePolicyProbes).isEqualTo(2);
            verify(fixture.heaters, never()).apply(any(), any(), any());
        }
    }

    @Test void pauseWireRetainsItsExactSchemaOneShape() throws Exception {
        var serialized = json.readTree(json.writeValueAsString(CloudRemoteCommandInboxTest.command()));
        assertThat(serialized.path("schemaVersion").asInt()).isEqualTo(1);
        for (String field : new String[] {"heaterId", "targetC", "expectedTargetC", "expectedDeviceBootId", "expectedDescriptorRevision", "expectedOperationState"}) {
            assertThat(serialized.has(field)).isFalse();
        }
    }

    private final class Fixture implements AutoCloseable {
        final HttpServer server;
        final CloudRemoteControlPolicyStore policy;
        final CloudRemoteCommandInbox inbox = mock(CloudRemoteCommandInbox.class);
        final CurrentExecutionPauseService pause = mock(CurrentExecutionPauseService.class);
        final HeaterTargetControlService heaters = mock(HeaterTargetControlService.class);
        final CloudRemoteControlWorker worker;
        final CloudWire.Credential credential;
        final AtomicInteger physicalAttempts = new AtomicInteger();
        final List<String> paths = new ArrayList<>(), statuses = new ArrayList<>(), codes = new ArrayList<>(), authorizations = new ArrayList<>();
        CloudControlWire.Command command = CloudRemoteCommandInboxTest.command();
        int policyHttpStatus = 200;
        String rejectedCommand, failedReceipt, invalidClaim;
        String claimBody;
        boolean rejectTemperaturePolicy;
        int temperaturePolicyProbes;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/gateway/", exchange -> {
                String path = exchange.getRequestURI().getPath(); paths.add(path);
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = exchange.getRequestBody().readAllBytes();
                int responseStatus = path.endsWith("control-policy") ? policyHttpStatus : 200;
                if (path.endsWith("control-policy") && json.readTree(body).has("temperatureControlVersion")) {
                    temperaturePolicyProbes++;
                    if (rejectTemperaturePolicy) responseStatus = 400;
                }
                if (path.endsWith("/claim") && json.readValue(body, CloudControlWire.Claim.class).commandId().equals(invalidClaim)) {
                    responseStatus = 404;
                }
                if (path.endsWith("/result")) {
                    var result = json.readValue(body, CloudControlWire.Result.class);
                    statuses.add(result.status()); codes.add(result.code());
                    if (result.commandId().equals(rejectedCommand)) responseStatus = "EXECUTING".equals(result.status()) ? 403 : 409;
                    if (result.commandId().equals(failedReceipt) && !"EXECUTING".equals(result.status())) responseStatus = 503;
                }
                byte[] response = path.endsWith("/claim") ? claimBody == null ? json.writeValueAsBytes(command) : claimBody.getBytes(StandardCharsets.UTF_8)
                        : "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, response.length);
                exchange.getResponseBody().write(response); exchange.close();
            });
            server.start();
            var properties = new CloudGatewayProperties();
            properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setCredentialFile(temporary.resolve(UUID.randomUUID().toString()).resolve("gateway.json").toString());
            credential = new CloudWire.Credential(command.gatewayId(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    1, "isolated-fixture-only", System.currentTimeMillis() + 3600000, "fixture", "fixture", "fixture");
            policy = new CloudRemoteControlPolicyStore(properties); policy.update(credential, true, 1);
            var source = mock(CloudSnapshotSource.class); when(source.sourceInstanceId()).thenReturn(command.sourceInstanceId());
            Map<String, CloudRemoteCommandInbox.Entry> entries = new HashMap<>();
            Set<String> reported = new HashSet<>();
            when(inbox.accept(any())).thenAnswer(invocation -> {
                CloudControlWire.Command claimed = invocation.getArgument(0);
                return entries.computeIfAbsent(claimed.commandId(), id -> new CloudRemoteCommandInbox.Entry(claimed, "RECEIVED", "OK", false));
            });
            when(inbox.executing(any())).thenReturn(true);
            when(inbox.pendingResults(any())).thenAnswer(invocation -> entries.values().stream()
                    .filter(entry -> !reported.contains(entry.command().commandId()) && !"RECEIVED".equals(entry.status())).toList());
            doAnswer(invocation -> { reported.add(invocation.getArgument(0)); return null; }).when(inbox).reported(any());
            doAnswer(invocation -> {
                CloudRemoteCommandInbox.Entry entry = invocation.getArgument(0); reported.add(entry.command().commandId()); return null;
            }).when(inbox).deliveryRejected(any());
            doAnswer(invocation -> {
                CloudControlWire.Command cmd = invocation.getArgument(0); CloudControlWire.Outcome outcome = invocation.getArgument(1);
                entries.put(cmd.commandId(), new CloudRemoteCommandInbox.Entry(cmd, outcome.status(), outcome.code(), false)); return null;
            }).when(inbox).complete(any(), any());
            when(pause.pause(any(), any(), any())).thenAnswer(invocation -> {
                CurrentExecutionPauseService.Checkpoint checkpoint = invocation.getArgument(1);
                CurrentExecutionPauseService.BeforeAction before = invocation.getArgument(2);
                checkpoint.check();
                try { before.executing(); } catch (Exception denied) { return new CloudControlWire.Outcome("UNKNOWN", "RESULT_UNKNOWN"); }
                checkpoint.check(); physicalAttempts.incrementAndGet();
                return new CloudControlWire.Outcome("CONFIRMED", "DEVICE_PAUSED");
            });
            when(heaters.apply(any(), any(), any())).thenAnswer(invocation -> {
                CurrentExecutionPauseService.Checkpoint checkpoint = invocation.getArgument(1);
                CurrentExecutionPauseService.BeforeAction before = invocation.getArgument(2);
                checkpoint.check(); before.executing(); checkpoint.check();
                return new CloudControlWire.Outcome("CONFIRMED", "HEATER_TARGET_CONFIRMED");
            });
            worker = new CloudRemoteControlWorker(properties, source, policy, inbox, pause, heaters);
            ReflectionTestUtils.setField(worker, "running", true);
            worker.connected(credential, command.bootId(), command.epoch(), () -> true);
        }
        byte[] notice() throws Exception { return json.writeValueAsBytes(new CloudControlWire.Notice(1, "COMMAND_AVAILABLE",
                command.commandId(), command.gatewayId(), command.bindingVersion(), command.bootId(), command.epoch())); }
        void tick() { ReflectionTestUtils.invokeMethod(worker, "tick"); }
        void temperatureCommand() {
            var view = policy.update(credential, null, true, policy.view(credential).revision());
            var old = command;
            command = new CloudControlWire.Command(2, old.commandId(), "SET_HEATER_TARGET", old.gatewayId(), old.bindingVersion(),
                    old.bootId(), old.epoch(), old.sourceInstanceId(), old.deviceUid(), old.localPrinterId(), null,
                    null, null, null, view.revision(), old.actorUserId(), old.actorUsername(), old.issuedAt(), old.expiresAt(),
                    "extruder", 205, java.math.BigDecimal.valueOf(200), UUID.randomUUID().toString(), 1L, "IDLE");
        }
        void nextCommand() {
            var old = command; long now = System.currentTimeMillis();
            command = new CloudControlWire.Command(1, UUID.randomUUID().toString(), old.action(), old.gatewayId(),
                    old.bindingVersion(), old.bootId(), old.epoch(), old.sourceInstanceId(), UUID.randomUUID().toString(), "2",
                    UUID.randomUUID().toString(), old.expectedFileName(), old.expectedFileSizeBytes(), old.expectedFilePositionBytes(),
                    old.policyRevision(), old.actorUserId(), old.actorUsername(), now, now + 15000);
        }
        @Override public void close() { worker.close(); server.stop(0); }
    }
}
