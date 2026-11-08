package com.example.farm.cloud;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/** C3 typed control contract; gateway credentials never enter a command payload. */
public final class CloudControlWire {
    private CloudControlWire() { }
    public record Notice(int schemaVersion, String type, String commandId, String gatewayId,
                         long bindingVersion, String bootId, long epoch) { }
    public record Session(String gatewayId, long bindingVersion, String bootId, long epoch,
                          String sourceInstanceId) { }
    public record Policy(String gatewayId, long bindingVersion, String bootId, long epoch,
                         String sourceInstanceId, boolean enabled, long policyRevision,
                         boolean temperatureEnabled, int temperatureControlVersion) { }
    public record Claim(String gatewayId, long bindingVersion, String bootId, long epoch, String commandId) { }
    public record Command(int schemaVersion, String commandId, String action, String gatewayId,
                          long bindingVersion, String bootId, long epoch, String sourceInstanceId,
                          String deviceUid, String localPrinterId, String expectedExecutionId,
                          String expectedFileName, Long expectedFileSizeBytes, Long expectedFilePositionBytes,
                          long policyRevision, String actorUserId, String actorUsername,
                          long issuedAt, long expiresAt,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String heaterId,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Integer targetC,
                          @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal expectedTargetC,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String expectedDeviceBootId,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Long expectedDescriptorRevision,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String expectedOperationState) {
        public Command(int schemaVersion, String commandId, String action, String gatewayId,
                       long bindingVersion, String bootId, long epoch, String sourceInstanceId,
                       String deviceUid, String localPrinterId, String expectedExecutionId,
                       String expectedFileName, Long expectedFileSizeBytes, Long expectedFilePositionBytes,
                       long policyRevision, String actorUserId, String actorUsername, long issuedAt, long expiresAt) {
            this(schemaVersion, commandId, action, gatewayId, bindingVersion, bootId, epoch, sourceInstanceId,
                    deviceUid, localPrinterId, expectedExecutionId, expectedFileName, expectedFileSizeBytes,
                    expectedFilePositionBytes, policyRevision, actorUserId, actorUsername, issuedAt, expiresAt,
                    null, null, null, null, null, null);
        }
    }
    public record Result(String gatewayId, long bindingVersion, String bootId, long epoch,
                         String commandId, String status, String code) { }
    public record Success(boolean success) { }
    public record Outcome(String status, String code) { }
}
