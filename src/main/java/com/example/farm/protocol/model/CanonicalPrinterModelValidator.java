package com.example.farm.protocol.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Structural and cross-reference checks shared by serialization and transport boundaries. */
public final class CanonicalPrinterModelValidator {
    private CanonicalPrinterModelValidator() { }

    public static void validate(CanonicalPrinterModel model) {
        if (model == null || model.modelVersion() != CanonicalPrinterModel.CURRENT_MODEL_VERSION
                || model.identity() == null || model.identity().sourceInstanceId() == null
                || model.identity().deviceUid() == null || model.identity().localPrinterId() == null
                || model.identity().localPrinterId().isBlank() || model.descriptor() == null
                || model.observation() == null) {
            throw new IllegalArgumentException("Canonical printer model is missing required fields");
        }
        var descriptor = model.descriptor();
        var observation = model.observation();
        if (descriptor.revision() < 1 || observation.id() == null || observation.deviceBootId() == null
                || observation.revision() < 1 || observation.connection() == null
                || observation.completeness() == null || observation.systemState() == null
                || observation.operationState() == null || observation.motion() == null
                || observation.descriptorRevision() != descriptor.revision()
                || observation.captureStartedAt() == null || observation.observedAt() == null
                || observation.captureStartedAt().isAfter(observation.observedAt())) {
            throw new IllegalArgumentException("Canonical printer model revisions or timestamps are invalid");
        }
        ids(descriptor.capabilities().stream().map(CanonicalPrinterModel.Capability::code).toList());
        descriptor.capabilities().forEach(capability -> {
            if (capability.support() == null || capability.evidence() == null) {
                throw new IllegalArgumentException("Capability support and evidence are required");
            }
        });
        Set<String> heaterIds = ids(descriptor.heaters().stream().map(CanonicalPrinterModel.HeaterDescriptor::id).toList());
        Set<String> toolIds = ids(descriptor.tools().stream().map(CanonicalPrinterModel.ToolDescriptor::id).toList());
        for (var tool : descriptor.tools()) {
            if (!heaterIds.containsAll(tool.heaterIds())) {
                throw new IllegalArgumentException("Tool references an unknown heater");
            }
        }
        Set<String> fanIds = ids(descriptor.fans().stream().map(CanonicalPrinterModel.FanDescriptor::id).toList());
        for (var tool : descriptor.tools()) {
            if (!fanIds.containsAll(tool.fanIds())) {
                throw new IllegalArgumentException("Tool references an unknown fan");
            }
        }
        if (descriptor.primaryToolId() != null && !toolIds.contains(descriptor.primaryToolId())) {
            throw new IllegalArgumentException("Primary tool is not declared in the descriptor");
        }
        if (observation.currentToolId() != null && !toolIds.contains(observation.currentToolId())) {
            throw new IllegalArgumentException("Current tool is not declared in the descriptor");
        }
        Set<String> observedHeaterIds = ids(observation.heaters().stream().map(CanonicalPrinterModel.HeaterState::id).toList());
        if (!heaterIds.containsAll(observedHeaterIds)) {
            throw new IllegalArgumentException("Observation references an unknown heater");
        }
        Set<String> observedFanIds = ids(observation.fans().stream().map(CanonicalPrinterModel.FanState::id).toList());
        if (!fanIds.containsAll(observedFanIds)) throw new IllegalArgumentException("Observation references an unknown fan");
        Set<String> axisIds = ids(descriptor.axes().stream().map(CanonicalPrinterModel.AxisDescriptor::id).toList());
        Set<String> observedAxisIds = ids(observation.motion().axes().stream().map(CanonicalPrinterModel.AxisState::id).toList());
        if (!axisIds.containsAll(observedAxisIds)) throw new IllegalArgumentException("Observation references an unknown axis");
        Set<String> sensorIds = ids(descriptor.sensors().stream().map(CanonicalPrinterModel.SensorDescriptor::id).toList());
        Set<String> observedSensorIds = ids(observation.sensors().stream().map(CanonicalPrinterModel.SensorState::id).toList());
        if (!sensorIds.containsAll(observedSensorIds)) throw new IllegalArgumentException("Observation references an unknown sensor");
        observation.heaters().forEach(heater -> {
            validateDecimal(heater.currentC());
            validateDecimal(heater.effectiveTargetC());
            validateDecimal(heater.activeTargetC());
            validateDecimal(heater.standbyTargetC());
            validateDecimal(heater.pwmRatio());
        });
        observation.motion().axes().forEach(axis -> {
            validateDecimal(axis.positionMm());
            validateBoolean(axis.homed());
        });
        validateDecimal(observation.motion().speedFactor());
        validateDecimal(observation.motion().flowFactor());
        observation.fans().forEach(fan -> {
            validateDecimal(fan.pwmRatio());
            validateDecimal(fan.rpm());
        });
        observation.sensors().forEach(sensor -> {
            validateJson(sensor.enabled());
            validateJson(sensor.reading());
        });
        observation.issues().forEach(issue -> {
            if (issue.code() == null || issue.severity() == null) {
                throw new IllegalArgumentException("Issue code and severity are required");
            }
        });
    }

    private static Set<String> ids(java.util.List<String> values) {
        Set<String> ids = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !ids.add(value)) {
                throw new IllegalArgumentException("Canonical identifiers must be non-empty and unique");
            }
        }
        return ids;
    }

    private static void validateDecimal(CanonicalPrinterModel.Measurement<BigDecimal> value) {
        if (value == null) return;
        if (value.quality() == null || value.source() == null) {
            throw new IllegalArgumentException("Measurement quality and source are required");
        }
        if (value.quality() == CanonicalPrinterModel.Quality.GOOD
                && (value.value() == null || value.observedAt() == null)) {
            throw new IllegalArgumentException("A good measurement requires a value and timestamp");
        }
        if (value.quality() != CanonicalPrinterModel.Quality.GOOD && value.value() != null) {
            throw new IllegalArgumentException("A non-good measurement cannot carry a value");
        }
    }

    private static void validateBoolean(CanonicalPrinterModel.Measurement<Boolean> value) {
        if (value == null) return;
        if (value.quality() == null || value.source() == null
                || (value.quality() == CanonicalPrinterModel.Quality.GOOD
                && (value.value() == null || value.observedAt() == null))
                || (value.quality() != CanonicalPrinterModel.Quality.GOOD && value.value() != null)) {
            throw new IllegalArgumentException("Boolean measurement quality is inconsistent");
        }
    }

    private static void validateJson(CanonicalPrinterModel.Measurement<com.fasterxml.jackson.databind.JsonNode> value) {
        if (value == null) return;
        if (value.quality() == null || value.source() == null
                || (value.quality() == CanonicalPrinterModel.Quality.GOOD
                && (value.value() == null || value.observedAt() == null))
                || (value.quality() != CanonicalPrinterModel.Quality.GOOD && value.value() != null)) {
            throw new IllegalArgumentException("Sensor measurement quality is inconsistent");
        }
    }
}
