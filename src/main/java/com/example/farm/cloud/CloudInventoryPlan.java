package com.example.farm.cloud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds bounded, immutable V3 inventory messages before any part is sent to Cloud. */
final class CloudInventoryPlan {
    private static final int MAX_DEVICES_PER_PART = 25;

    private CloudInventoryPlan() { }

    static List<CloudWire.CanonicalPacket> build(ObjectMapper json, String gatewayId, long bindingVersion,
                                                  String bootId, long epoch, long firstSequence,
                                                  Instant observedAt, String sourceInstanceId, long revision,
                                                  List<CloudSnapshotSource.InventoryDevice> devices,
                                                  int maxMessageBytes, int maxPrinters,
                                                  int maxParts, int maxInventoryBytes) {
        if (json == null || devices == null || devices.size() > maxPrinters || maxPrinters < 1
                || maxParts < 1 || maxParts > 8 || maxInventoryBytes < 1 || maxMessageBytes < 1
                || firstSequence < 1 || revision < 1) {
            throw new IllegalArgumentException("Cloud inventory limits or metadata are invalid");
        }
        UUID.fromString(gatewayId);
        UUID.fromString(bootId);
        UUID.fromString(sourceInstanceId);
        if (bindingVersion < 1 || epoch < 1 || observedAt == null) {
            throw new IllegalArgumentException("Cloud inventory identity is invalid");
        }

        var groups = new ArrayList<List<CloudSnapshotSource.InventoryDevice>>();
        var current = new ArrayList<CloudSnapshotSource.InventoryDevice>();
        for (var device : devices) {
            current.add(device);
            if (current.size() > Math.min(maxPrinters, MAX_DEVICES_PER_PART)
                    || !fits(json, gatewayId, bindingVersion, bootId, epoch, firstSequence + groups.size(),
                    observedAt, sourceInstanceId, revision, groups.size(), maxParts, current, maxMessageBytes)) {
                current.removeLast();
                if (current.isEmpty()) { throw new IllegalArgumentException("Cloud inventory device exceeds message limit"); }
                groups.add(List.copyOf(current));
                current.clear();
                current.add(device);
                if (!fits(json, gatewayId, bindingVersion, bootId, epoch, firstSequence + groups.size(),
                        observedAt, sourceInstanceId, revision, groups.size(), maxParts, current, maxMessageBytes)) {
                    throw new IllegalArgumentException("Cloud inventory device exceeds message limit");
                }
            }
        }
        if (!current.isEmpty() || groups.isEmpty()) { groups.add(List.copyOf(current)); }
        if (groups.size() > maxParts || groups.size() > 8) {
            throw new IllegalArgumentException("Cloud inventory requires too many parts");
        }

        UUID inventoryId = UUID.randomUUID();
        var messages = new ArrayList<CloudWire.CanonicalPacket>(groups.size());
        int totalBytes = 0;
        for (int index = 0; index < groups.size(); index++) {
            var payload = new CloudWire.InventoryPayload(inventoryId.toString(), revision, sourceInstanceId,
                    index, groups.size(), groups.get(index));
            var message = new CloudWire.CanonicalPacket(3, UUID.randomUUID().toString(), "INVENTORY", gatewayId,
                    bindingVersion, bootId, epoch, firstSequence + index, observedAt, payload);
            int bytes = serializedSize(json, message);
            if (bytes > maxMessageBytes) { throw new IllegalArgumentException("Cloud inventory part exceeds message limit"); }
            totalBytes += bytes;
            messages.add(message);
        }
        if (totalBytes > maxInventoryBytes) { throw new IllegalArgumentException("Cloud inventory exceeds total byte limit"); }
        return List.copyOf(messages);
    }

    private static boolean fits(ObjectMapper json, String gatewayId, long bindingVersion, String bootId, long epoch,
                                long sequence, Instant observedAt, String sourceInstanceId, long revision,
                                int partIndex, int partCount,
                                List<CloudSnapshotSource.InventoryDevice> devices, int maxMessageBytes) {
        var payload = new CloudWire.InventoryPayload("00000000-0000-0000-0000-000000000000", revision,
                sourceInstanceId, partIndex, partCount, devices);
        var message = new CloudWire.CanonicalPacket(3, "00000000-0000-0000-0000-000000000000", "INVENTORY",
                gatewayId, bindingVersion, bootId, epoch, sequence, observedAt, payload);
        return serializedSize(json, message) <= maxMessageBytes;
    }

    private static int serializedSize(ObjectMapper json, CloudWire.CanonicalPacket message) {
        try { return json.writeValueAsBytes(message).length; }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Cannot encode Cloud inventory", invalid); }
    }
}
