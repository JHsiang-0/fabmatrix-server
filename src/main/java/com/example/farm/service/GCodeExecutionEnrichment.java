package com.example.farm.service;

import com.example.farm.protocol.file.GCodeFileInspection;
import com.example.farm.protocol.model.CanonicalPrinterModel;
import com.example.farm.protocol.model.CanonicalPrinterModel.Execution;
import com.example.farm.protocol.model.CanonicalPrinterModel.Measurement;
import com.example.farm.protocol.model.CanonicalPrinterModel.MeasurementSource;
import com.example.farm.protocol.model.CanonicalPrinterModel.Quality;

import java.math.BigDecimal;
import java.time.Instant;

/** Adds proven file data and explicitly derived estimates to an existing observation. */
public final class GCodeExecutionEnrichment {
    private GCodeExecutionEnrichment() { }

    public static CanonicalPrinterModel apply(CanonicalPrinterModel model, GCodeFileInspection inspection) {
        var observation = model.observation();
        var execution = observation.currentExecution();
        if (execution == null) return model;
        var metadata = inspection.metadata();
        Instant at = observation.observedAt();
        var timing = execution.timing();
        Measurement<BigDecimal> estimated = timing.slicerEstimatedTotalSeconds();
        if (missing(estimated) && metadata.estimatedPrintSeconds() != null
                && metadata.estimatedPrintSeconds() >= 0 && metadata.estimatedPrintSeconds() <= 315576000L) {
            estimated = new Measurement<>(BigDecimal.valueOf(metadata.estimatedPrintSeconds()), Quality.GOOD,
                    at, MeasurementSource.SLICER_ESTIMATE);
        }
        var total = execution.layers().total();
        if (missing(total) && metadata.totalLayers() != null && metadata.totalLayers() > 0) {
            total = new Measurement<>(metadata.totalLayers(), Quality.GOOD, at, MeasurementSource.SLICER_ESTIMATE);
        }
        var current = execution.layers().current();
        var position = execution.filePositionBytes();
        if (missing(current) && available(position)) {
            Long layer = inspection.currentLayerAt(position.value());
            if (layer != null && (!available(total) || layer <= total.value())) {
                current = new Measurement<>(layer, Quality.GOOD, at, MeasurementSource.SERVER_DERIVED);
            }
        }
        var remaining = execution.estimatedRemaining();
        var elapsed = available(timing.printSeconds()) ? timing.printSeconds() : timing.activeSeconds();
        if ((remaining == null || missing(remaining.seconds())) && available(estimated) && available(elapsed)
                && elapsed.value().signum() >= 0 && estimated.value().compareTo(elapsed.value()) > 0) {
            remaining = new CanonicalPrinterModel.Remaining(new Measurement<>(estimated.value().subtract(elapsed.value()),
                    Quality.GOOD, at, MeasurementSource.SERVER_DERIVED), CanonicalPrinterModel.RemainingMethod.SERVER_MODEL, null);
        }
        var file = execution.file();
        var enriched = new Execution(execution.id(), execution.source(), execution.kind(), execution.localFarmJobId(),
                execution.firstObservedAt(), execution.startedAt(), new CanonicalPrinterModel.FileSummary(
                        file == null ? metadata.fileName() : file.name(),
                        file == null || file.sizeBytes() == null ? metadata.sizeBytes() : file.sizeBytes(),
                        file == null || file.sha256() == null ? metadata.sha256() : file.sha256()),
                execution.progressPercent(), execution.progressMethod(), execution.filePositionBytes(),
                new CanonicalPrinterModel.Timing(timing.printSeconds(), timing.totalSeconds(), timing.activeSeconds(),
                        timing.warmupSeconds(), timing.pausedSeconds(), estimated),
                new CanonicalPrinterModel.Layers(current, total), execution.filamentLengthMm(), remaining);
        var result = new CanonicalPrinterModel.Observation(observation.id(), observation.deviceBootId(), observation.revision(),
                observation.descriptorRevision(), observation.captureStartedAt(), observation.observedAt(), observation.completeness(),
                observation.connection(), observation.systemState(), observation.operationState(), observation.currentToolId(),
                observation.heaters(), observation.fans(), observation.motion(), observation.sensors(), enriched,
                observation.lastExecutionResult(), observation.issues());
        var descriptor = model.descriptor();
        boolean hasLayers = available(current) || available(total);
        boolean hasRemaining = remaining != null && available(remaining.seconds());
        var capabilities = descriptor.capabilities().stream().map(capability -> {
            boolean supported = "READ_LAYER_PROGRESS".equals(capability.code()) && hasLayers
                    || "READ_REMAINING_TIME".equals(capability.code()) && hasRemaining;
            return supported && capability.support() != CanonicalPrinterModel.SupportState.SUPPORTED
                    ? new CanonicalPrinterModel.Capability(capability.code(), CanonicalPrinterModel.SupportState.SUPPORTED,
                        CanonicalPrinterModel.CapabilityEvidence.ADAPTER, at) : capability;
        }).toList();
        var enrichedDescriptor = new CanonicalPrinterModel.Descriptor(descriptor.revision(), descriptor.name(),
                descriptor.machineNumber(), descriptor.firmwareFamily(), descriptor.firmwareVersion(), descriptor.connectionMode(),
                descriptor.machineMode(), descriptor.emulated(), descriptor.primaryToolId(), descriptor.primaryBedHeaterId(),
                descriptor.tools(), descriptor.heaters(), descriptor.fans(), descriptor.axes(), descriptor.sensors(), capabilities,
                descriptor.locationLabel(), descriptor.configuredMaterial(), descriptor.configuredNozzleDiameterMm());
        return new CanonicalPrinterModel(model.modelVersion(), model.identity(), enrichedDescriptor, result);
    }

    private static boolean missing(Measurement<?> value) {
        return value == null || value.quality() == Quality.UNAVAILABLE || value.quality() == Quality.UNSUPPORTED;
    }

    private static boolean available(Measurement<?> value) {
        return value != null && value.quality() == Quality.GOOD && value.value() != null;
    }
}
