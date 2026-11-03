package com.example.farm.cloud;

/** Single-threaded token bucket for application payload bytes, including retries. */
final class CloudBandwidthLimiter {
    private static final int MAX_MESSAGE_BYTES = 65_536;
    private static final int MIN_BYTES_PER_SECOND = 16_384;
    private static final int MAX_BYTES_PER_SECOND = 10 * 1024 * 1024;

    private final int bytesPerSecond;
    private final int capacity;
    private double available;
    private long lastRefillNanos;

    CloudBandwidthLimiter(int bytesPerSecond, long nowNanos) {
        if (bytesPerSecond < MIN_BYTES_PER_SECOND || bytesPerSecond > MAX_BYTES_PER_SECOND) {
            throw new IllegalArgumentException("Cloud upload rate must be between 16384 and 10485760 bytes per second");
        }
        this.bytesPerSecond = bytesPerSecond;
        this.capacity = Math.max(bytesPerSecond, MAX_MESSAGE_BYTES);
        this.available = capacity;
        this.lastRefillNanos = nowNanos;
    }

    boolean tryAcquire(int bytes, long nowNanos) {
        if (bytes < 0 || bytes > MAX_MESSAGE_BYTES) { throw new IllegalArgumentException("Invalid Cloud message size"); }
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            available = Math.min(capacity, available + (double) elapsed * bytesPerSecond / 1_000_000_000d);
            lastRefillNanos = nowNanos;
        }
        if (available < bytes) { return false; }
        available -= bytes;
        return true;
    }
}
