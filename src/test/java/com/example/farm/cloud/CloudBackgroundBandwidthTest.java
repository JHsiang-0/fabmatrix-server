package com.example.farm.cloud;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudBackgroundBandwidthTest {
    @Test void stalledForegroundPacketStopsBackgroundUntilForegroundHasAcquiredItsBytes() {
        var limiter = new CloudBandwidthLimiter(16384, 0);
        assertTrue(limiter.tryAcquire(65536, 0));
        assertFalse(limiter.tryAcquire(65536, 0));
        assertFalse(limiter.tryAcquireBackground(1024, 1_000_000_000L));
        assertFalse(limiter.tryAcquireBackground(1024, 4_000_000_000L));
        assertTrue(limiter.tryAcquire(65536, 4_000_000_000L));
        assertFalse(limiter.tryAcquireBackground(1024, 4_000_000_000L));
        assertTrue(limiter.tryAcquireBackground(1024, 5_000_000_000L));
    }

    @Test void backgroundBodyIsChunkedAndCancellationDoesNotConsumeFutureStatusBudget() throws Exception {
        var limiter = new CloudBandwidthLimiter(16384, System.nanoTime());
        var body = new CloudFileMetadataSync.BackgroundInput(new byte[2500], limiter, () -> true);
        byte[] chunk = new byte[3000];
        assertEquals(1024, body.read(chunk));
        assertEquals(1024, body.read(chunk));
        assertEquals(452, body.read(chunk));
        assertEquals(-1, body.read(chunk));
        var cancelled = new CloudFileMetadataSync.BackgroundInput(new byte[1], limiter, () -> false);
        assertThrows(InterruptedIOException.class, cancelled::read);
        assertTrue(limiter.tryAcquire(60000, System.nanoTime()));
    }
}
