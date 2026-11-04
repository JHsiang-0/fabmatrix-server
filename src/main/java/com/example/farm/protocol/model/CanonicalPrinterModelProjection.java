package com.example.farm.protocol.model;

import com.fasterxml.jackson.databind.JsonNode;

import static com.example.farm.protocol.model.CanonicalPrinterModel.*;

/** Produces the bounded public wire model while preserving measurement quality. */
public final class CanonicalPrinterModelProjection {
    private CanonicalPrinterModelProjection() { }

    public static CanonicalPrinterModel project(CanonicalPrinterModel model) {
        var original = model.observation();
        var heaters = original.heaters().stream().map(h -> new HeaterState(h.id(), required(h.currentC()),
                required(h.effectiveTargetC()), required(h.activeTargetC()), required(h.standbyTargetC()),
                required(h.pwmRatio()), h.mode())).toList();
        var fans = original.fans().stream().map(f -> new FanState(f.id(), required(f.pwmRatio()),
                required(f.rpm()))).toList();
        var motion = original.motion();
        var axes = motion.axes().stream().map(a -> new AxisState(a.id(), required(a.positionMm()),
                required(a.homed()))).toList();
        var safeMotion = new Motion(motion.coordinateSpace(), axes, required(motion.speedFactor()),
                required(motion.flowFactor()));
        var sensors = original.sensors().stream().map(s -> new SensorState(s.id(), safeSensor(s.enabled(), true),
                safeSensor(s.reading(), false))).toList();
        var issues = original.issues().stream().map(issue -> new Issue(issue.code(), issue.severity(), null)).toList();
        var observation = new Observation(original.id(), original.deviceBootId(), original.revision(),
                original.descriptorRevision(), original.captureStartedAt(), original.observedAt(),
                original.completeness(), original.connection(), original.systemState(), original.operationState(),
                original.currentToolId(), heaters, fans, safeMotion, sensors, execution(original.currentExecution()),
                original.lastExecutionResult(), issues);
        return new CanonicalPrinterModel(model.modelVersion(), model.identity(), model.descriptor(), observation);
    }

    private static Execution execution(Execution execution) {
        if (execution == null) { return null; }
        var timing = execution.timing();
        var layers = execution.layers();
        var remaining = execution.estimatedRemaining();
        return new Execution(execution.id(), execution.source(), execution.kind(), null, execution.firstObservedAt(),
                execution.startedAt(), safeFile(execution.file()), required(execution.progressPercent()), execution.progressMethod(),
                required(execution.filePositionBytes()),
                new Timing(required(timing.printSeconds()), required(timing.totalSeconds()), required(timing.activeSeconds()),
                        required(timing.warmupSeconds()), required(timing.pausedSeconds()),
                        required(timing.slicerEstimatedTotalSeconds())),
                new Layers(required(layers.current()), required(layers.total())), required(execution.filamentLengthMm()),
                remaining == null ? null : new Remaining(required(remaining.seconds()), remaining.method(), remaining.confidence()));
    }

    private static <T> Measurement<T> required(Measurement<T> measurement) {
        return measurement == null ? new Measurement<>(null, Quality.UNAVAILABLE, null, MeasurementSource.NONE) : measurement;
    }

    private static Measurement<JsonNode> safeSensor(Measurement<JsonNode> measurement, boolean booleanOnly) {
        if (measurement == null || measurement.value() == null) { return required(measurement); }
        if (measurement.value().isNull()) {
            return new Measurement<>(null, measurement.quality(), measurement.observedAt(), measurement.source());
        }
        JsonNode value = measurement.value();
        if (value.isBoolean() || (!booleanOnly && value.isNumber())) { return measurement; }
        return new Measurement<>(null, Quality.INVALID, null, MeasurementSource.NONE);
    }

    private static FileSummary safeFile(FileSummary file) {
        if (file == null) { return null; }
        String name = file.name();
        if (name != null) {
            int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            name = name.substring(separator + 1).replaceAll("[:\\p{Cntrl}]", "_");
            if (name.length() > 128) { name = name.substring(0, 128); }
            if (name.isBlank()) { name = null; }
        }
        Long size = file.sizeBytes() != null && file.sizeBytes() >= 0 ? file.sizeBytes() : null;
        String hash = file.sha256() != null && file.sha256().matches("(?i)[0-9a-f]{64}") ? file.sha256() : null;
        return new FileSummary(name, size, hash);
    }
}
