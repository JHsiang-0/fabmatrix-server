package com.example.farm.cloud;

import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.service.PrinterCacheService;
import com.example.farm.service.PrinterService;
import com.example.farm.protocol.observation.PrinterObservationParser;
import com.example.farm.protocol.observation.PrinterObservationView;
import com.example.farm.protocol.observation.PrinterObservation;
import com.example.farm.protocol.model.CanonicalPrinterModelMapper;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.service.CanonicalPrinterStateService;
import com.example.farm.service.PrinterEventOutbox;
import com.example.farm.protocol.model.CanonicalPrinterEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CloudGatewayTest {
    @TempDir Path temp;

    @Test void protocolNegotiationAdvertisesOnlyImplementedVersionsAndLegacyFallbackPreservesSelection() {
        assertThat(CloudGatewayAgent.supportedSchemaVersions(false, false)).containsExactly(1);
        assertThat(CloudGatewayAgent.supportedSchemaVersions(true, false)).containsExactly(1, 2);
        assertThat(CloudGatewayAgent.supportedSchemaVersions(false, true)).containsExactly(1, 3);
        assertThat(CloudGatewayAgent.supportedSchemaVersions(true, true)).containsExactly(1, 2, 3);
        assertThat(CloudGatewayAgent.legacyFallback(false).selectedSchemaVersion()).isEqualTo(1);
        assertThat(CloudGatewayAgent.legacyFallback(true).selectedSchemaVersion()).isEqualTo(2);
    }

    @Test void uploadBudgetLimitsAggregatePayloadAndRefillsOverTime() {
        var limiter = new CloudBandwidthLimiter(65_536, 0);
        assertThat(limiter.tryAcquire(65_536, 0)).isTrue();
        assertThat(limiter.tryAcquire(1, 0)).isFalse();
        assertThat(limiter.tryAcquire(32_768, 500_000_000L)).isTrue();
        assertThat(limiter.tryAcquire(1, 500_000_000L)).isFalse();
        assertThat(limiter.tryAcquire(32_768, 1_000_000_000L)).isTrue();
        assertThatThrownBy(() -> new CloudBandwidthLimiter(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire(65_537, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void legacyProtocolsPublishSnapshotsWithoutWaitingForV3Inventory() throws Exception {
        for (int version : List.of(1, 2)) {
            var properties = new CloudGatewayProperties();
            properties.setStateDirectory(temp.resolve("legacy-" + version).toString());
            properties.setObservationV2Enabled(version == 2);
            var source = mock(CloudSnapshotSource.class);
            when(source.capture()).thenReturn(List.of());
            when(source.captureV2()).thenReturn(List.of());
            var mqtt = mock(MqttClient.class);
            var agent = connectedAgent(properties, source, mqtt, version);

            ReflectionTestUtils.invokeMethod(agent, "tick");

            var bytes = ArgumentCaptor.forClass(byte[].class);
            verify(mqtt).publish(endsWith("up/snapshot"), bytes.capture(), eq(1), eq(false));
            assertThat(new ObjectMapper().readTree(bytes.getValue()).path("schemaVersion").asInt()).isEqualTo(version);
        }
    }

    @Test void duplicateAcknowledgementAdvancesAnIntermediateInventoryPart() throws Exception {
        var properties = new CloudGatewayProperties();
        properties.setStateDirectory(temp.resolve("inventory").toString());
        var source = mock(CloudSnapshotSource.class);
        String sourceId = UUID.randomUUID().toString();
        var devices = java.util.stream.IntStream.range(0, 26).mapToObj(index ->
                new CloudSnapshotSource.InventoryDevice(
                        new CloudSnapshotSource.InventoryIdentity(sourceId, UUID.randomUUID().toString(), "" + (index + 1)),
                        new CloudSnapshotSource.InventoryDescriptorSummary("Printer " + index, null, "KLIPPER"))).toList();
        when(source.sourceInstanceId()).thenReturn(sourceId);
        when(source.captureInventoryDevices(sourceId)).thenReturn(devices);
        var mqtt = mock(MqttClient.class);
        var agent = connectedAgent(properties, source, mqtt, 3);

        ReflectionTestUtils.invokeMethod(agent, "tick");
        var firstBytes = ArgumentCaptor.forClass(byte[].class);
        verify(mqtt).publish(endsWith("up/inventory"), firstBytes.capture(), eq(1), eq(false));
        var first = new ObjectMapper().readTree(firstBytes.getValue());
        assertThat(first.path("payload").path("partIndex").asInt()).isZero();
        ReflectionTestUtils.setField(agent, "receivedAck", new CloudWire.Ack(first.path("messageId").asText(),
                first.path("epoch").asLong(), first.path("sequence").asLong(), "DUPLICATE", "OK"));

        ReflectionTestUtils.invokeMethod(agent, "tick");

        var allBytes = ArgumentCaptor.forClass(byte[].class);
        verify(mqtt, times(2)).publish(endsWith("up/inventory"), allBytes.capture(), eq(1), eq(false));
        assertThat(new ObjectMapper().readTree(allBytes.getAllValues().get(1))
                .path("payload").path("partIndex").asInt()).isEqualTo(1);
    }

    @Test void inventoryChangesAreReconciledAndRevisionSurvivesAgentRestart() throws Exception {
        var properties = new CloudGatewayProperties();
        properties.setStateDirectory(temp.resolve("inventory-refresh").toString());
        var source = mock(CloudSnapshotSource.class);
        String sourceId = UUID.randomUUID().toString();
        var first = new CloudSnapshotSource.InventoryDevice(
                new CloudSnapshotSource.InventoryIdentity(sourceId, UUID.randomUUID().toString(), "1"),
                new CloudSnapshotSource.InventoryDescriptorSummary("Printer 1", null, "KLIPPER"));
        var second = new CloudSnapshotSource.InventoryDevice(
                new CloudSnapshotSource.InventoryIdentity(sourceId, UUID.randomUUID().toString(), "2"),
                new CloudSnapshotSource.InventoryDescriptorSummary("Printer 2", null, "RRF"));
        when(source.sourceInstanceId()).thenReturn(sourceId);
        when(source.captureInventoryDevices(sourceId)).thenReturn(List.of(first));
        var mqtt = mock(MqttClient.class);
        var agent = connectedAgent(properties, source, mqtt, 3);
        ReflectionTestUtils.setField(agent, "lastCapture", System.currentTimeMillis());
        ReflectionTestUtils.setField(agent, "lastDescriptorScan", System.currentTimeMillis());
        ReflectionTestUtils.invokeMethod(agent, "tick");
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(mqtt).publish(endsWith("up/inventory"), bytes.capture(), eq(1), eq(false));
        var initial = new ObjectMapper().readTree(bytes.getValue());
        ReflectionTestUtils.setField(agent, "receivedAck", new CloudWire.Ack(initial.path("messageId").asText(),
                initial.path("epoch").asLong(), initial.path("sequence").asLong(), "ACCEPTED_INVENTORY", "OK"));
        ReflectionTestUtils.invokeMethod(agent, "tick");

        when(source.captureInventoryDevices(sourceId)).thenReturn(List.of(first, second));
        ReflectionTestUtils.setField(agent, "lastInventoryScan", 0L);
        ReflectionTestUtils.invokeMethod(agent, "tick");
        var sent = ArgumentCaptor.forClass(byte[].class);
        verify(mqtt, times(2)).publish(endsWith("up/inventory"), sent.capture(), eq(1), eq(false));
        var updated = new ObjectMapper().readTree(sent.getAllValues().get(1));
        assertThat(updated.path("payload").path("devices")).hasSize(2);
        assertThat(updated.path("payload").path("revision").asLong())
                .isGreaterThan(initial.path("payload").path("revision").asLong());

        var restartedMqtt = mock(MqttClient.class);
        var restarted = connectedAgent(properties, source, restartedMqtt, 3);
        ReflectionTestUtils.invokeMethod(restarted, "tick");
        var restartedBytes = ArgumentCaptor.forClass(byte[].class);
        verify(restartedMqtt).publish(endsWith("up/inventory"), restartedBytes.capture(), eq(1), eq(false));
        assertThat(new ObjectMapper().readTree(restartedBytes.getValue()).path("payload").path("revision").asLong())
                .isGreaterThan(updated.path("payload").path("revision").asLong());
    }

    private CloudGatewayAgent connectedAgent(CloudGatewayProperties properties, CloudSnapshotSource source,
                                              MqttClient mqtt, int version) throws Exception {
        return connectedAgent(properties, source, mqtt, version, null);
    }

    private CloudGatewayAgent connectedAgent(CloudGatewayProperties properties, CloudSnapshotSource source,
                                              MqttClient mqtt, int version,
                                              org.springframework.beans.factory.ObjectProvider<PrinterEventOutbox> outboxes) throws Exception {
        when(mqtt.isConnected()).thenReturn(true);
        var agent = new CloudGatewayAgent(properties, source, outboxes);
        String gatewayId = UUID.randomUUID().toString();
        var credential = new CloudWire.Credential(gatewayId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, "fixture", System.currentTimeMillis() + 60000, "fixture", "fixture", "fixture/");
        ReflectionTestUtils.setField(agent, "credential", credential);
        ReflectionTestUtils.setField(agent, "http", mock(java.net.http.HttpClient.class));
        ReflectionTestUtils.setField(agent, "mqtt", mqtt);
        ReflectionTestUtils.setField(agent, "protocol", new CloudWire.Protocol(version, 2, List.of(), 65536, 200, 8, 524288));
        ReflectionTestUtils.setField(agent, "protocolNegotiated", true);
        ReflectionTestUtils.setField(agent, "registered", true);
        ReflectionTestUtils.setField(agent, "running", true);
        ReflectionTestUtils.setField(agent, "epoch", 1L);
        return agent;
    }

    @Test void eventOutboxIsRemovedOnlyAfterCloudCommitOrDuplicateAcknowledgement() throws Exception {
        for (String result : List.of("ACCEPTED", "DUPLICATE", "REJECTED")) {
            var properties = new CloudGatewayProperties();
            properties.setObservationV3Enabled(true);
            properties.setStateDirectory(temp.resolve("event-" + result).toString());
            var source = mock(CloudSnapshotSource.class);
            when(source.captureCanonical()).thenReturn(List.of());
            var outbox = mock(PrinterEventOutbox.class);
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<PrinterEventOutbox> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(outbox);
            var sourceId = UUID.randomUUID();
            var event = new CanonicalPrinterEvent(UUID.randomUUID(), sourceId, UUID.randomUUID(), "42", null,
                    CanonicalPrinterEvent.Type.DEVICE_CONNECTED, Instant.now().minusSeconds(3600),
                    CanonicalPrinterEvent.Severity.INFO, "DEVICE_CONNECTED", null, null);
            when(outbox.findReady(1)).thenReturn(List.of(event), List.of());
            var mqtt = mock(MqttClient.class);
            var agent = connectedAgent(properties, source, mqtt, 3, provider);
            ReflectionTestUtils.setField(agent, "inventoryComplete", true);
            ReflectionTestUtils.setField(agent, "canonicalSourceInstanceId", sourceId.toString());
            ReflectionTestUtils.setField(agent, "lastInventoryScan", System.currentTimeMillis());
            ReflectionTestUtils.setField(agent, "lastCapture", System.currentTimeMillis());
            ReflectionTestUtils.setField(agent, "lastDescriptorScan", System.currentTimeMillis());

            ReflectionTestUtils.invokeMethod(agent, "tick");
            var bytes = ArgumentCaptor.forClass(byte[].class);
            verify(mqtt).publish(endsWith("up/event"), bytes.capture(), eq(1), eq(false));
            verify(outbox).recordAttempt(event.eventId());
            verify(outbox, never()).acknowledge(any());
            var packet = new ObjectMapper().readTree(bytes.getValue());
            assertThat(packet.path("payload").path("event").path("eventId").asText()).isEqualTo(event.eventId().toString());
            ReflectionTestUtils.setField(agent, "receivedAck", new CloudWire.Ack(packet.path("messageId").asText(),
                    packet.path("epoch").asLong(), packet.path("sequence").asLong(), result, "fixture"));

            ReflectionTestUtils.invokeMethod(agent, "tick");
            verify(outbox, times("REJECTED".equals(result) ? 0 : 1)).acknowledge(event.eventId());
        }
    }

    @Test void canonicalInventoryPlanPartitionsAndBoundsEveryV3Message() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        String gatewayId = UUID.randomUUID().toString();
        String bootId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        var devices = java.util.stream.IntStream.range(0, 51).mapToObj(index ->
                new CloudSnapshotSource.InventoryDevice(
                        new CloudSnapshotSource.InventoryIdentity(sourceId, UUID.randomUUID().toString(), "" + (index + 1)),
                        new CloudSnapshotSource.InventoryDescriptorSummary("Printer " + index, null, "KLIPPER")))
                .toList();

        var plan = CloudInventoryPlan.build(json, gatewayId, 1, bootId, 1, 1, Instant.now(), sourceId,
                1, devices, 65536, 200, 8, 524288);

        assertThat(plan).hasSize(3);
        assertThat(plan).extracting(CloudWire.CanonicalPacket::type).containsOnly("INVENTORY");
        assertThat(plan).extracting(CloudWire.CanonicalPacket::schemaVersion).containsOnly(3);
        assertThat(plan).extracting(CloudWire.CanonicalPacket::sequence).containsExactly(1L, 2L, 3L);
        assertThat(plan).allSatisfy(part -> {
            assertThat(part.payload().partCount()).isEqualTo(3);
            assertThat(part.payload().devices()).hasSizeLessThanOrEqualTo(25);
            assertThat(json.writeValueAsBytes(part)).hasSizeLessThanOrEqualTo(65536);
        });
        assertThat(plan).extracting(part -> part.payload().inventoryId()).containsOnly(plan.getFirst().payload().inventoryId());
        assertThatThrownBy(() -> CloudInventoryPlan.build(json, gatewayId, 1, bootId, 1, 1, Instant.now(),
                sourceId, 1, devices, 65536, 200, 2, 524288)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void inventoryAtDeviceLimitFitsEightMessagesWithoutLosingIdentities() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        String sourceId = UUID.randomUUID().toString();
        var devices = java.util.stream.IntStream.range(0, 200).mapToObj(index ->
                new CloudSnapshotSource.InventoryDevice(
                        new CloudSnapshotSource.InventoryIdentity(sourceId, UUID.randomUUID().toString(), "" + index),
                        new CloudSnapshotSource.InventoryDescriptorSummary("设".repeat(100), "M".repeat(50), "KLIPPER")))
                .toList();
        var plan = CloudInventoryPlan.build(json, UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(),
                1, 1, Instant.now(), sourceId, 1, devices, 65536, 200, 8, 524288);
        assertThat(plan).hasSize(8);
        assertThat(plan.stream().flatMap(part -> part.payload().devices().stream()).toList()).containsExactlyElementsOf(devices);
        assertThat(plan).allSatisfy(part -> assertThat(json.writeValueAsBytes(part)).hasSizeLessThanOrEqualTo(65536));
        var tooMany = new java.util.ArrayList<>(devices);
        tooMany.add(devices.getFirst());
        assertThatThrownBy(() -> CloudInventoryPlan.build(json, UUID.randomUUID().toString(), 1,
                UUID.randomUUID().toString(), 1, 1, Instant.now(), sourceId, 1, tooMany,
                65536, 200, 8, 524288)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void canonicalDescriptorPacketUsesCloudV3EnvelopeAndPrivacyFilteredModel() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        Printer printer = asset(42L);
        printer.setDeviceUid(UUID.randomUUID().toString());
        printer.setApiKey("must-not-be-published");
        var at = Instant.now();
        var observation = new PrinterObservation(at, at, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        var model = CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, observation, null, true, at);
        var packet = new CloudWire.CanonicalDescriptorPacket(3, UUID.randomUUID().toString(),
                "PRINTER_DESCRIPTOR", UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(),
                1, 1, at, new CloudWire.DescriptorPayload(model.identity(), model.descriptor()));

        var root = json.readTree(json.writeValueAsBytes(packet));
        var payloadKeys = new java.util.HashSet<String>();
        root.path("payload").fieldNames().forEachRemaining(payloadKeys::add);
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(3);
        assertThat(root.path("type").asText()).isEqualTo("PRINTER_DESCRIPTOR");
        assertThat(payloadKeys).containsExactlyInAnyOrder("identity", "descriptor");
        assertThat(root.path("payload").path("descriptor").path("revision").asLong()).isEqualTo(1);
        assertThat(json.writeValueAsString(packet)).doesNotContain("must-not-be-published");
    }

    @Test void expiredDescriptorIsRebuiltWithAFreshEnvelopeAfterReconnect() throws Exception {
        var properties = new CloudGatewayProperties();
        properties.setStateDirectory(temp.resolve("expired-descriptor").toString());
        var source = mock(CloudSnapshotSource.class);
        var mqtt = mock(MqttClient.class);
        var agent = connectedAgent(properties, source, mqtt, 3);
        var sourceId = UUID.randomUUID();
        var printer = asset(42L);
        printer.setDeviceUid(UUID.randomUUID().toString());
        var at = Instant.now();
        var observation = new PrinterObservation(at, at, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        var model = CanonicalPrinterModelMapper.map(printer, sourceId, UUID.randomUUID(), 1, 1,
                observation, null, true, at);
        when(source.sourceInstanceId()).thenReturn(sourceId.toString());
        when(source.captureCanonical()).thenReturn(List.of(new CloudSnapshotSource.CanonicalAsset("42",
                printer.getDeviceUid(), printer.getName(), null, "KLIPPER", model)));
        var expired = new CloudWire.CanonicalDescriptorPacket(3, UUID.randomUUID().toString(), "PRINTER_DESCRIPTOR",
                UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), 1, 1, at.minusSeconds(61),
                new CloudWire.DescriptorPayload(model.identity(), model.descriptor()));
        ReflectionTestUtils.setField(agent, "inventoryComplete", true);
        ReflectionTestUtils.setField(agent, "canonicalSourceInstanceId", sourceId.toString());
        ReflectionTestUtils.setField(agent, "lastInventoryScan", System.currentTimeMillis());
        ReflectionTestUtils.setField(agent, "lastCapture", System.currentTimeMillis());
        ReflectionTestUtils.setField(agent, "descriptorPlan", List.of(expired));
        ReflectionTestUtils.invokeMethod(agent, "prepareDescriptorPending");

        ReflectionTestUtils.invokeMethod(agent, "tick");

        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(mqtt).publish(endsWith("up/descriptor"), bytes.capture(), eq(1), eq(false));
        var sent = new ObjectMapper().readTree(bytes.getValue());
        assertThat(sent.path("messageId").asText()).isNotEqualTo(expired.messageId());
        assertThat(Instant.parse(sent.path("observedAt").asText())).isAfter(at.minusSeconds(5));
    }

    @Test void rejectedDescriptorDoesNotBlockOtherDevices() throws Exception {
        var properties = new CloudGatewayProperties();
        properties.setStateDirectory(temp.resolve("rejected-descriptor").toString());
        var source = mock(CloudSnapshotSource.class);
        var mqtt = mock(MqttClient.class);
        var agent = connectedAgent(properties, source, mqtt, 3);
        var sourceId = UUID.randomUUID();
        var at = Instant.now();
        var observation = new PrinterObservation(at, at, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        var first = asset(1L);
        first.setDeviceUid(UUID.randomUUID().toString());
        var second = asset(2L);
        second.setDeviceUid(UUID.randomUUID().toString());
        var firstModel = CanonicalPrinterModelMapper.map(first, sourceId, UUID.randomUUID(), 1, 1, observation, null, true, at);
        var secondModel = CanonicalPrinterModelMapper.map(second, sourceId, UUID.randomUUID(), 1, 1, observation, null, true, at);
        var firstPacket = new CloudWire.CanonicalDescriptorPacket(3, UUID.randomUUID().toString(), "PRINTER_DESCRIPTOR",
                UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), 1, 1, at,
                new CloudWire.DescriptorPayload(firstModel.identity(), firstModel.descriptor()));
        var secondPacket = new CloudWire.CanonicalDescriptorPacket(3, UUID.randomUUID().toString(), "PRINTER_DESCRIPTOR",
                firstPacket.gatewayId(), 1, firstPacket.bootId(), 1, 2, at,
                new CloudWire.DescriptorPayload(secondModel.identity(), secondModel.descriptor()));
        ReflectionTestUtils.setField(agent, "inventoryComplete", true);
        ReflectionTestUtils.setField(agent, "canonicalSourceInstanceId", sourceId.toString());
        ReflectionTestUtils.setField(agent, "lastInventoryScan", System.currentTimeMillis());
        ReflectionTestUtils.setField(agent, "lastCapture", System.currentTimeMillis());
        ReflectionTestUtils.setField(agent, "descriptorPlan", List.of(firstPacket, secondPacket));
        ReflectionTestUtils.invokeMethod(agent, "prepareDescriptorPending");
        ReflectionTestUtils.setField(agent, "receivedAck", new CloudWire.Ack(firstPacket.messageId(), 1, 1,
                "REJECTED", "CANONICAL_MODEL_INVALID"));

        ReflectionTestUtils.invokeMethod(agent, "tick");

        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(mqtt).publish(endsWith("up/descriptor"), bytes.capture(), eq(1), eq(false));
        assertThat(new ObjectMapper().readTree(bytes.getValue()).path("payload").path("identity")
                .path("localPrinterId").asText()).isEqualTo("2");
    }

    @Test void canonicalStateWaitsForDescriptorAndIsSentOnceAfterCommitAcknowledgement() throws Exception {
        var properties = new CloudGatewayProperties();
        properties.setStateDirectory(temp.resolve("canonical-state").toString());
        properties.setObservationV3Enabled(true);
        var source = mock(CloudSnapshotSource.class);
        var mqtt = mock(MqttClient.class);
        var agent = connectedAgent(properties, source, mqtt, 3);
        var printer = asset(42L);
        printer.setDeviceUid(UUID.randomUUID().toString());
        printer.setApiKey("private-fixture-key");
        var sourceId = UUID.randomUUID();
        var at = Instant.now();
        var raw = new ObjectMapper().readTree("""
                {"webhooks":{"state":"ready"},"print_stats":{"state":"standby"},
                 "extruder":{"temperature":0,"target":210}}
                """);
        var observation = PrinterObservationParser.klipper(raw, at, at);
        var model = CanonicalPrinterModelMapper.map(printer, sourceId, UUID.randomUUID(),
                1, 1, observation, null, true, at);
        var asset = new CloudSnapshotSource.CanonicalAsset("42", printer.getDeviceUid(), printer.getName(),
                null, "KLIPPER", model);
        when(source.captureCanonical()).thenReturn(List.of(asset));
        ReflectionTestUtils.setField(agent, "inventoryComplete", true);
        ReflectionTestUtils.setField(agent, "canonicalSourceInstanceId", sourceId.toString());
        ReflectionTestUtils.setField(agent, "lastInventoryScan", System.currentTimeMillis());
        ReflectionTestUtils.setField(agent, "lastCapture", System.currentTimeMillis());
        ReflectionTestUtils.setField(agent, "lastDescriptorScan", System.currentTimeMillis());

        ReflectionTestUtils.invokeMethod(agent, "tick");
        verify(mqtt, never()).publish(endsWith("up/printer-state"), any(byte[].class), eq(1), eq(false));
        @SuppressWarnings("unchecked")
        var descriptorRevisions = (java.util.Map<String, Long>) ReflectionTestUtils.getField(agent, "acknowledgedDescriptorRevisions");
        descriptorRevisions.put("42", 1L);
        @SuppressWarnings("unchecked")
        var descriptorUids = (java.util.Map<String, String>) ReflectionTestUtils.getField(agent, "acknowledgedDescriptorDeviceUids");
        descriptorUids.put("42", printer.getDeviceUid());
        ReflectionTestUtils.invokeMethod(agent, "tick");

        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(mqtt).publish(endsWith("up/printer-state"), bytes.capture(), eq(1), eq(false));
        var packet = new ObjectMapper().readTree(bytes.getValue());
        assertThat(packet.path("payload").path("observation").path("heaters").get(0)
                .path("pwmRatio").path("quality").asText()).isEqualTo("UNAVAILABLE");
        assertThat(new String(bytes.getValue(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("private-fixture-key");
        ReflectionTestUtils.setField(agent, "receivedAck", new CloudWire.Ack(packet.path("messageId").asText(),
                packet.path("epoch").asLong(), packet.path("sequence").asLong(), "ACCEPTED", "OK"));
        ReflectionTestUtils.invokeMethod(agent, "tick");
        verify(mqtt, times(1)).publish(endsWith("up/printer-state"), any(byte[].class), eq(1), eq(false));
    }

    @Test void canonicalProjectionRemovesPrivatePathsDiagnosticsAndNonScalarSensorData() throws Exception {
        var printer = asset(7L);
        printer.setDeviceUid(UUID.randomUUID().toString());
        var at = Instant.now();
        var source = new PrinterObservation(at, at, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        var model = CanonicalPrinterModelMapper.map(printer, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, source, null, true, at);
        var unavailable = new CanonicalPrinterModel.Measurement<java.math.BigDecimal>(null,
                CanonicalPrinterModel.Quality.UNSUPPORTED, null, CanonicalPrinterModel.MeasurementSource.NONE);
        var unknownCount = new CanonicalPrinterModel.Measurement<Long>(null,
                CanonicalPrinterModel.Quality.UNSUPPORTED, null, CanonicalPrinterModel.MeasurementSource.NONE);
        var execution = new CanonicalPrinterModel.Execution(UUID.randomUUID(), CanonicalPrinterModel.ExecutionSource.FARM,
                CanonicalPrinterModel.ExecutionKind.PRINT, "local-sensitive-job", at, at,
                new CanonicalPrinterModel.FileSummary("D:\\private-network\\fixture.gcode", 123L, "invalid-hash"),
                unavailable, CanonicalPrinterModel.ProgressMethod.UNKNOWN, unknownCount,
                new CanonicalPrinterModel.Timing(unavailable, unavailable, unavailable, unavailable, unavailable, unavailable),
                new CanonicalPrinterModel.Layers(unknownCount, unknownCount), unavailable, null);
        var unsafeReading = new CanonicalPrinterModel.Measurement<com.fasterxml.jackson.databind.JsonNode>(
                com.fasterxml.jackson.databind.node.TextNode.valueOf("private-sensor-secret"),
                CanonicalPrinterModel.Quality.GOOD, at, CanonicalPrinterModel.MeasurementSource.DEVICE);
        var o = model.observation();
        var missingSensor = new CanonicalPrinterModel.Measurement<com.fasterxml.jackson.databind.JsonNode>(
                com.fasterxml.jackson.databind.node.NullNode.instance, CanonicalPrinterModel.Quality.UNSUPPORTED,
                null, CanonicalPrinterModel.MeasurementSource.NONE);
        var changed = new CanonicalPrinterModel.Observation(o.id(), o.deviceBootId(), o.revision(), o.descriptorRevision(),
                o.captureStartedAt(), o.observedAt(), o.completeness(), o.connection(), o.systemState(), o.operationState(),
                o.currentToolId(), o.heaters(), o.fans(), o.motion(),
                List.of(new CanonicalPrinterModel.SensorState("sensor", missingSensor, unsafeReading)), execution,
                o.lastExecutionResult(), List.of(new CanonicalPrinterModel.Issue(
                        CanonicalPrinterModel.IssueCode.DEVICE_FAULT, CanonicalPrinterModel.Severity.WARNING, "private-fault-text")));

        var d = model.descriptor();
        var descriptor = new CanonicalPrinterModel.Descriptor(d.revision(), d.name(), d.machineNumber(),
                d.firmwareFamily(), d.firmwareVersion(), d.connectionMode(), d.machineMode(), d.emulated(),
                d.primaryToolId(), d.primaryBedHeaterId(), d.tools(), d.heaters(), d.fans(), d.axes(),
                List.of(new CanonicalPrinterModel.SensorDescriptor("sensor", "Sensor", "OTHER", null)),
                d.capabilities(), d.locationLabel(), d.configuredMaterial(), d.configuredNozzleDiameterMm());
        com.example.farm.protocol.model.CanonicalPrinterModelValidator.validate(
                new CanonicalPrinterModel(model.modelVersion(), model.identity(), descriptor, changed));

        var projected = CloudCanonicalStateMapper.project(new CanonicalPrinterModel(model.modelVersion(),
                model.identity(), descriptor, changed));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(projected);
        assertThat(json).doesNotContain("local-sensitive-job", "private-network", "private-sensor-secret", "private-fault-text");
        assertThat(projected.observation().currentExecution().file().name()).isEqualTo("fixture.gcode");
        assertThat(projected.observation().currentExecution().file().sha256()).isNull();
        assertThat(projected.observation().sensors().getFirst().reading().quality())
                .isEqualTo(CanonicalPrinterModel.Quality.INVALID);
        assertThat(projected.observation().sensors().getFirst().enabled().quality())
                .isEqualTo(CanonicalPrinterModel.Quality.UNSUPPORTED);
        assertThat(projected.observation().sensors().getFirst().enabled().value()).isNull();
    }

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

    @Test void canonicalCaptureKeepsAssetIdentityAndReadsOnlyPersistedFullModels() {
        PrinterService printers = mock(PrinterService.class);
        PrinterCacheService cache = mock(PrinterCacheService.class);
        CanonicalPrinterStateService states = mock(CanonicalPrinterStateService.class);
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        Printer first = asset(1L);
        first.setDeviceUid(java.util.UUID.randomUUID().toString());
        first.setName("Canonical printer");
        first.setMachineNumber("M-1");
        when(printers.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(first, asset(2L)));
        var at = java.time.Instant.now();
        var observation = new PrinterObservation(at, at, PrinterObservation.Completeness.PARTIAL,
                PrinterObservation.SystemState.READY, PrinterObservation.OperationState.IDLE,
                List.of(), List.of(), null, null);
        var model = CanonicalPrinterModelMapper.map(first, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                1, 1, observation, null, true, at);
        when(states.findLatestForCloud(1L)).thenReturn(model);
        when(states.findLatestForCloud(2L)).thenThrow(
                new CanonicalPrinterStateService.InvalidStoredModelException(new IllegalArgumentException("private fixture error")));
        when(provider.getIfAvailable()).thenReturn(states);

        var source = new CloudSnapshotSource(printers, cache, provider);
        var captured = source.captureCanonical();
        assertThat(captured).hasSize(2);
        assertThat(captured.getFirst().localPrinterId()).isEqualTo("1");
        assertThat(captured.getFirst().deviceUid()).isEqualTo(first.getDeviceUid());
        assertThat(captured.getFirst().name()).isEqualTo("Canonical printer");
        assertThat(captured.getFirst().model()).isSameAs(model);
        assertThat(captured.get(1).model()).isNull();
        verify(states).findLatestForCloud(1L);
        verify(states).findLatestForCloud(2L);
        doThrow(new IllegalStateException("storage unavailable")).when(states).findLatestForCloud(2L);
        assertThatThrownBy(source::captureCanonical).isInstanceOf(IllegalStateException.class)
                .hasMessage("storage unavailable");
    }

    @Test void inventoryCaptureUsesStableIdsAndRejectsUnknownFirmwareWithoutDroppingDevices() {
        PrinterService printers = mock(PrinterService.class);
        Printer asset = asset(1L);
        asset.setDeviceUid(UUID.randomUUID().toString());
        asset.setFirmwareType("RRF");
        asset.setMachineNumber("M-1");
        when(printers.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(asset));
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var source = new CloudSnapshotSource(printers, mock(PrinterCacheService.class), provider);
        String sourceId = UUID.randomUUID().toString();

        var inventory = source.captureInventoryDevices(sourceId);

        assertThat(inventory).containsExactly(new CloudSnapshotSource.InventoryDevice(
                new CloudSnapshotSource.InventoryIdentity(sourceId, asset.getDeviceUid(), "1"),
                new CloudSnapshotSource.InventoryDescriptorSummary("Printer 1", "M-1", "RRF")));
        asset.setFirmwareType("UNKNOWN");
        assertThatThrownBy(() -> source.captureInventoryDevices(sourceId)).isInstanceOf(IllegalStateException.class);
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
