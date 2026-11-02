package com.example.farm.protocol.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Immutable, allowlisted device event aligned with the Cloud V3 PRINTER_EVENT payload. */
public record CanonicalPrinterEvent(UUID eventId, UUID sourceInstanceId, UUID deviceUid,
                                    String localPrinterId, UUID executionId, Type type,
                                    Instant occurredAt, Severity severity, String code,
                                    String safeMessage, UUID correlationId) {
    private static final Set<Type> TYPES = Set.of(Type.values());

    public CanonicalPrinterEvent {
        if (eventId == null || sourceInstanceId == null || deviceUid == null || localPrinterId == null
                || localPrinterId.isBlank() || localPrinterId.length() > 64 || type == null || occurredAt == null
                || severity == null || code == null || !code.matches("[A-Z0-9_.-]{1,64}")
                || (safeMessage != null && (safeMessage.length() > 256
                || safeMessage.chars().anyMatch(Character::isISOControl))) || !TYPES.contains(type)) {
            throw new IllegalArgumentException("Canonical printer event contains invalid fields");
        }
    }

    /** Cloud event type vocabulary, intentionally excludes arbitrary firmware event names. */
    public enum Type {
        DEVICE_CONNECTED, DEVICE_DISCONNECTED, SYSTEM_FAULT, EXECUTION_STARTED,
        EXECUTION_PAUSED, EXECUTION_RESUMED, EXECUTION_ENDED, MATERIAL_WARNING,
        MONITOR_DISABLED, GATEWAY_LOST
    }

    public enum Severity { INFO, WARNING, ERROR, CRITICAL }

}
