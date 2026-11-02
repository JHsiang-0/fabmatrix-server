package com.example.farm.cloud;

import com.example.farm.protocol.model.CanonicalPrinterModel;

import static com.example.farm.protocol.model.CanonicalPrinterModel.*;

/** Produces the bounded public wire model while preserving measurement quality. */
final class CloudCanonicalStateMapper {
    private CloudCanonicalStateMapper() { }

    static CanonicalPrinterModel project(CanonicalPrinterModel model) {
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
        var sensors = original.sensors().stream().map(s -> new SensorState(s.id(), required(s.enabled()),
                required(s.reading()))).toList();
        var observation = new Observation(original.id(), original.deviceBootId(), original.revision(),
                original.descriptorRevision(), original.captureStartedAt(), original.observedAt(),
                original.completeness(), original.connection(), original.systemState(), original.operationState(),
                original.currentToolId(), heaters, fans, safeMotion, sensors, execution(original.currentExecution()),
                original.lastExecutionResult(), original.issues());
        return new CanonicalPrinterModel(model.modelVersion(), model.identity(), model.descriptor(), observation);
    }

    private static Execution execution(Execution execution) {
        if (execution == null) { return null; }
        var timing = execution.timing();
        var layers = execution.layers();
        var remaining = execution.estimatedRemaining();
        return new Execution(execution.id(), execution.source(), execution.kind(), null, execution.firstObservedAt(),
                execution.startedAt(), execution.file(), required(execution.progressPercent()), execution.progressMethod(),
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
}
