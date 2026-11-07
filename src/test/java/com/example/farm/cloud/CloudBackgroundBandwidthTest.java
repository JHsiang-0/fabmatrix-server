package com.example.farm.cloud;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    @Test void closingARequestBodyUnblocksAReadWaitingBehindStatusTraffic() throws Exception {
        var limiter = stalledLimiter();
        var waiting = new CountDownLatch(1);
        var body = new CloudFileMetadataSync.BackgroundInput(new byte[1], limiter, () -> {
            waiting.countDown();
            return true;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> assertThrows(InterruptedIOException.class, body::read));
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            body.close();
            result.get(2, TimeUnit.SECONDS);
        } finally {
            body.close();
            executor.shutdownNow();
        }
    }

    @Test void requestDeadlineBoundsTheWaitEvenWhenThePublisherHasNotClosedItsInput() {
        var body = new CloudFileMetadataSync.BackgroundInput(new byte[1], stalledLimiter(), () -> true,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(InterruptedIOException.class, body::read));
    }

    private CloudBandwidthLimiter stalledLimiter() {
        long now = System.nanoTime();
        var limiter = new CloudBandwidthLimiter(16384, now);
        assertTrue(limiter.tryAcquire(65536, now));
        assertFalse(limiter.tryAcquire(65536, now));
        return limiter;
    }
}
