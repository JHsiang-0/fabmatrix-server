package com.example.farm.cloud;

import java.time.Instant;
import java.util.List;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class CloudWire {
    private CloudWire() { }
    public record Credential(String gatewayId, String organizationId, String farmId, long bindingVersion,
                             String credential, long expiresAt, String mqttClientId, String mqttUsername, String topicPrefix) {
        @Override public String toString() { return "CloudCredential[gatewayId=" + gatewayId + "]"; }
    }
    public record Printer(String localPrinterId, String name, String firmwareType, String connectionStatus,
                          String printState, boolean monitorEnabled, Instant observedAt, Double progress,
                          Double toolTemperature, Double bedTemperature,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Observation observation) {
        public Printer(String localPrinterId, String name, String firmwareType, String connectionStatus,
                       String printState, boolean monitorEnabled, Instant observedAt, Double progress,
                       Double toolTemperature, Double bedTemperature) {
            this(localPrinterId, name, firmwareType, connectionStatus, printState, monitorEnabled, observedAt,
                    progress, toolTemperature, bedTemperature, null);
        }
    }
    public record Measurement(BigDecimal value, String quality, Instant observedAt) { }
    public record Heater(String id, String role, String mode, Measurement currentC, Measurement effectiveTargetC,
                         Measurement activeTargetC, Measurement standbyTargetC) { }
    public record Tool(String id, List<String> heaterIds) { }
    public record Execution(boolean simulation, Measurement progressPercent, Measurement printSeconds,
                            Measurement totalSeconds, Measurement activeSeconds, Measurement slicerEstimatedSeconds,
                            Measurement remainingSeconds, Measurement filamentLengthMm) { }
    public record Observation(Instant captureStartedAt, Instant observedAt, String completeness, String systemState,
                              String operationState, List<Heater> heaters, List<Tool> tools, String currentToolId,
                              Execution execution) { }
    public record Payload(String agentVersion, int printerCount, List<Printer> printers) { }
    public record Packet(int schemaVersion, String messageId, String type, String gatewayId, long bindingVersion,
                         String bootId, long epoch, long sequence, Instant observedAt, Payload payload) { }
    public record Ack(String messageId, long epoch, long sequence, String result, String code) { }
    public record Session(long epoch, long lastSequence) { }
    public record Boot(String gatewayId, long bindingVersion, String bootId) { }
    public record ProtocolRequest(String gatewayId, long bindingVersion, List<Integer> supportedSchemaVersions) { }
    public record Protocol(int selectedSchemaVersion, int modelVersion, List<String> capabilities,
                           int maxMessageBytes, int maxPrinters, int maxInventoryParts, int maxInventoryBytes) { }
}
