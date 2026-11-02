package com.example.farm.common;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.common.utils.CacheStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.awaitility.Awaitility.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrinterLockTest {

    @Test
    void tryExecuteWaitsForLocalLockToBeReleased() throws Exception {
        var entered = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> PrinterLock.execute(990007L, null, () -> {
                entered.countDown();
                try { TimeUnit.MILLISECONDS.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(PrinterLock.tryExecute(990007L, null, () -> "ok")).isEqualTo("ok");
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void tryExecuteRetriesDistributedLockAndSucceeds() {
        CacheStore redis = mock(CacheStore.class);
        when(redis.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(false, true);
        assertThat(PrinterLock.tryExecute(990006L, redis, () -> "ok")).isEqualTo("ok");
        verify(redis, org.mockito.Mockito.times(2)).tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void tryExecuteRejectsDeviceAfterBoundedWait() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> PrinterLock.execute(990005L, null, () -> {
                entered.countDown();
                waitFor(release);
                return null;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> PrinterLock.tryExecute(990005L, null, () -> "unexpected"))
                    .isInstanceOf(BusinessException.class);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(PrinterLock.tryExecute(990005L, null, () -> "ok")).isEqualTo("ok");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void callerThatCapturedTheMutexBeforeLockingCannotRaceWithANewMutex() throws Exception {
        Field registryField = PrinterLock.class.getDeclaredField("LOCAL_LOCKS");
        registryField.setAccessible(true);
        Map<Long, ReentrantLock> registry = (Map<Long, ReentrantLock>) registryField.get(null);
        long id = 990003L;
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch allowDelayedLock = new CountDownLatch(1);
        ReentrantLock mutex = new ReentrantLock() {
            @Override
            public void lock() {
                if (Thread.currentThread().getName().equals("delayed-local-lock")) {
                    captured.countDown();
                    waitFor(allowDelayedLock);
                }
                super.lock();
            }
        };
        registry.put(id, mutex);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch thirdEntered = new CountDownLatch(1);
        CountDownLatch releaseThird = new CountDownLatch(1);
        CountDownLatch delayedEntered = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> PrinterLock.execute(id, null, () -> {
                firstEntered.countDown();
                waitFor(releaseFirst);
                return null;
            }));
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> delayed = executor.submit(() -> {
                Thread.currentThread().setName("delayed-local-lock");
                return PrinterLock.execute(id, null, () -> {
                    delayedEntered.countDown();
                    return null;
                });
            });
            assertThat(captured.await(2, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            Future<?> third = executor.submit(() -> PrinterLock.execute(id, null, () -> {
                thirdEntered.countDown();
                waitFor(releaseThird);
                return null;
            }));
            assertThat(thirdEntered.await(2, TimeUnit.SECONDS)).isTrue();
            allowDelayedLock.countDown();
            await().atMost(java.time.Duration.ofSeconds(2))
                    .until(() -> mutex.hasQueuedThreads() || delayedEntered.getCount() == 0);
            assertThat(delayedEntered.getCount()).isEqualTo(1);
            releaseThird.countDown();
            third.get(2, TimeUnit.SECONDS);
            delayed.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            releaseThird.countDown();
            allowDelayedLock.countDown();
            executor.shutdownNow();
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the lock fixture");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @Test
    void localLockSerializesSamePrinterAndAllowsReentrantCalls() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger actions = new AtomicInteger();
        try {
            Future<Integer> first = executor.submit(() -> PrinterLock.execute(403L, null, () -> {
                firstEntered.countDown();
                PrinterLock.execute(403L, null, () -> actions.incrementAndGet());
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return actions.incrementAndGet();
            }));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = executor.submit(() -> PrinterLock.execute(403L, null,
                    actions::incrementAndGet));
            releaseFirst.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(3);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void redisEditionUsesLongLeaseAndOwnedRelease() {
        CacheStore cacheStore = mock(CacheStore.class);
        when(cacheStore.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);

        PrinterLock.execute(403L, cacheStore, () -> {
            PrinterLock.ensureValid(403L);
            return null;
        });

        verify(cacheStore).tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS));
        verify(cacheStore).releaseLockIfOwned(anyString(), anyString());
    }

    @Test
    void redisCleanupFailureKeepsTheActionResultAndReleasesTheLocalMutex() throws Exception {
        CacheStore cacheStore = mock(CacheStore.class);
        when(cacheStore.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(cacheStore.releaseLockIfOwned(anyString(), anyString()))
                .thenThrow(new IllegalStateException("isolated release failure"));

        assertThat(PrinterLock.execute(990004L, cacheStore, () -> "committed")).isEqualTo("committed");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> next = executor.submit(() -> PrinterLock.execute(990004L, null, () -> "next"));
            assertThat(next.get(2, TimeUnit.SECONDS)).isEqualTo("next");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void lostRedisLeaseIsRejectedBeforeTheNextDeviceStage() throws Exception {
        CacheStore cacheStore = mock(CacheStore.class);
        when(cacheStore.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(cacheStore.renewLockIfOwned(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertThatThrownBy(() -> PrinterLock.execute(403L, cacheStore, () -> {
            try {
                Field heldLocks = PrinterLock.class.getDeclaredField("HELD_LOCKS");
                heldLocks.setAccessible(true);
                Map<Long, Object> current = (Map<Long, Object>) ((ThreadLocal<?>) heldLocks.get(null)).get();
                Object lease = current.get(403L);
                Method renew = lease.getClass().getDeclaredMethod("renew");
                renew.setAccessible(true);
                renew.invoke(lease);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
            PrinterLock.ensureValid(403L);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(503L);

        verify(cacheStore, org.mockito.Mockito.never()).releaseLockIfOwned(anyString(), anyString());
    }

    @Test
    void refusesInvalidPrinterIdBeforeAcquiringAnyLock() {
        assertThatThrownBy(() -> PrinterLock.execute(0L, null, () -> null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(400L);
    }
}
