package com.example.farm.cloud;

/** Shared token bucket with foreground priority for status, inventory and acknowledgement retries. */
final class CloudBandwidthLimiter {
    private static final int MAX_MESSAGE_BYTES = 65_536;
    private static final int MIN_BYTES_PER_SECOND = 16_384;
    private static final int MAX_BYTES_PER_SECOND = 10 * 1024 * 1024;

    private final int bytesPerSecond;
    private final int capacity;
    private double available;
    private long lastRefillNanos;
    private int foregroundWaiting;

    CloudBandwidthLimiter(int bytesPerSecond, long nowNanos) {
        if (bytesPerSecond < MIN_BYTES_PER_SECOND || bytesPerSecond > MAX_BYTES_PER_SECOND) {
            throw new IllegalArgumentException("Cloud upload rate must be between 16384 and 10485760 bytes per second");
        }
        this.bytesPerSecond = bytesPerSecond;
        this.capacity = Math.max(bytesPerSecond, MAX_MESSAGE_BYTES);
        this.available = capacity;
        this.lastRefillNanos = nowNanos;
    }

    synchronized boolean tryAcquire(int bytes, long nowNanos) {
        if (bytes < 0 || bytes > MAX_MESSAGE_BYTES) { throw new IllegalArgumentException("Invalid Cloud message size"); }
        refill(nowNanos);
        if (available < bytes) {
            foregroundWaiting = bytes;
            return false;
        }
        available -= bytes;
        foregroundWaiting = 0;
        return true;
    }

    synchronized boolean tryAcquireBackground(int bytes, long nowNanos) {
        if (bytes < 0 || bytes > 1024) { throw new IllegalArgumentException("Invalid background upload chunk"); }
        refill(nowNanos);
        int reserve = Math.min(8192, bytesPerSecond / 2);
        if (foregroundWaiting > 0 || available < reserve + bytes) return false;
        available -= bytes;
        return true;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            available = Math.min(capacity, available + (double) elapsed * bytesPerSecond / 1_000_000_000d);
            lastRefillNanos = nowNanos;
        }
    }
}
