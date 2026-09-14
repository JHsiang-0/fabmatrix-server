package com.example.farm.common;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.common.utils.RedisUtil;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrinterLockTest {

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
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);

        PrinterLock.execute(403L, redisUtil, () -> {
            PrinterLock.ensureValid(403L);
            return null;
        });

        verify(redisUtil).tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS));
        verify(redisUtil).releaseLockIfOwned(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void lostRedisLeaseIsRejectedBeforeTheNextDeviceStage() throws Exception {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.tryLock(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redisUtil.renewLockIfOwned(anyString(), anyString(), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertThatThrownBy(() -> PrinterLock.execute(403L, redisUtil, () -> {
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

        verify(redisUtil, org.mockito.Mockito.never()).releaseLockIfOwned(anyString(), anyString());
    }

    @Test
    void refusesInvalidPrinterIdBeforeAcquiringAnyLock() {
        assertThatThrownBy(() -> PrinterLock.execute(0L, null, () -> null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(400L);
    }
}
