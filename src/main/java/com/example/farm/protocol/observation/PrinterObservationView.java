package com.example.farm.protocol.observation;

import com.example.farm.entity.Printer;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 将短期缓存解释为当前或最后已知观测，不改变采集时间，也不提供在线判定。 */
public record PrinterObservationView(Availability availability, PrinterObservation current,
                                     PrinterObservation lastKnown) {
    public enum Availability {
        CURRENT, NO_OBSERVATION, MONITOR_DISABLED, CONNECTION_UNKNOWN,
        COMMUNICATION_FAILURE, EXPIRED, INVALID_TIMESTAMP, INCONSISTENT
    }

    /**
     * printer 必须是调用方从可信数据源获取的最新设备健康状态；缓存不是在线证明。
     * maxAge 由调用方按照轮询策略指定，不能以缓存 TTL 代替。
     */
    public static PrinterObservationView evaluate(Printer printer, PrinterObservation observation,
                                                  Instant now, Duration maxAge) {
        Objects.requireNonNull(printer, "printer");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("maxAge must be positive");
        }
        Availability reason;
        if (!Boolean.TRUE.equals(printer.getMonitorEnabled())) {
            reason = Availability.MONITOR_DISABLED;
        } else if (!"ONLINE".equalsIgnoreCase(printer.getConnectionStatus())
                || printer.getConsecutiveFailureCount() == null || printer.getConsecutiveFailureCount() < 0) {
            reason = Availability.CONNECTION_UNKNOWN;
        } else if (printer.getConsecutiveFailureCount() != null && printer.getConsecutiveFailureCount() > 0) {
            reason = Availability.COMMUNICATION_FAILURE;
        } else if (observation == null) {
            reason = Availability.NO_OBSERVATION;
        } else if (observation.captureStartedAt() == null || observation.observedAt() == null
                || observation.captureStartedAt().isAfter(observation.observedAt())
                || observation.observedAt().isAfter(now)) {
            reason = Availability.INVALID_TIMESTAMP;
        } else if (observation.completeness() == null
                || observation.completeness() == PrinterObservation.Completeness.INCONSISTENT) {
            reason = Availability.INCONSISTENT;
        } else if (Duration.between(observation.observedAt(), now).compareTo(maxAge) > 0) {
            reason = Availability.EXPIRED;
        } else {
            reason = Availability.CURRENT;
        }
        return new PrinterObservationView(reason, reason == Availability.CURRENT ? observation : null, observation);
    }
}
