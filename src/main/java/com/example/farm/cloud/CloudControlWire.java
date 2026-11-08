package com.example.farm.cloud;

/** C3 typed control contract; gateway credentials never enter a command payload. */
public final class CloudControlWire {
    private CloudControlWire() { }
    public record Notice(int schemaVersion, String type, String commandId, String gatewayId,
                         long bindingVersion, String bootId, long epoch) { }
    public record Session(String gatewayId, long bindingVersion, String bootId, long epoch,
                          String sourceInstanceId) { }
    public record Policy(String gatewayId, long bindingVersion, String bootId, long epoch,
                         String sourceInstanceId, boolean enabled, long policyRevision) { }
    public record Claim(String gatewayId, long bindingVersion, String bootId, long epoch, String commandId) { }
    public record Command(int schemaVersion, String commandId, String action, String gatewayId,
                          long bindingVersion, String bootId, long epoch, String sourceInstanceId,
                          String deviceUid, String localPrinterId, String expectedExecutionId,
                          String expectedFileName, Long expectedFileSizeBytes, Long expectedFilePositionBytes,
                          long policyRevision, String actorUserId, String actorUsername,
                          long issuedAt, long expiresAt) { }
    public record Result(String gatewayId, long bindingVersion, String bootId, long epoch,
                         String commandId, String status, String code) { }
    public record Success(boolean success) { }
    public record Outcome(String status, String code) { }
}
