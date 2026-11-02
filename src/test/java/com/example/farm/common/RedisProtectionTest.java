package com.example.farm.common;

import com.example.farm.common.constant.CacheKeyConstant;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.common.utils.CacheStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisProtectionTest {

    @Mock
    private CacheStore cacheStore;

    @Test
    void recordsLoginFailureWithFifteenMinuteExpiry() {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.LOGIN_FAIL_COUNT, "admin");
        when(cacheStore.increment(key, 1, 15, TimeUnit.MINUTES)).thenReturn(1L);

        int count = new LoginProtectUtil(cacheStore).recordLoginFail("admin");

        assertThat(count).isEqualTo(1);
        verify(cacheStore).increment(key, 1, 15, TimeUnit.MINUTES);
    }

    @Test
    void considersOnlyFiveOrMoreFailuresLocked() {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.LOGIN_FAIL_COUNT, "admin");
        when(cacheStore.getString(key)).thenReturn("5");
        assertThat(new LoginProtectUtil(cacheStore).isLocked("admin")).isTrue();

        when(cacheStore.getString(key)).thenReturn("bad-value");
        assertThat(new LoginProtectUtil(cacheStore).isLocked("admin")).isFalse();
    }

    @Test
    void readsRemainingLockTimeAndClearsFailureRecord() {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.LOGIN_FAIL_COUNT, "admin");
        when(cacheStore.getString(key)).thenReturn("5");
        when(cacheStore.getExpire(key, TimeUnit.MINUTES)).thenReturn(9L);

        LoginProtectUtil protectUtil = new LoginProtectUtil(cacheStore);

        assertThat(protectUtil.getRemainingLockTime("admin")).isEqualTo(9L);
        protectUtil.clearLoginFail("admin");
        verify(cacheStore).delete(key);
    }

    @Test
    void disableAndEnableUserUseDedicatedRedisFlag() {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.USER_DISABLED, 7L);
        LoginProtectUtil protectUtil = new LoginProtectUtil(cacheStore);

        protectUtil.disableUser(7L);
        verify(cacheStore).setString(key, "1");

        when(cacheStore.hasKey(key)).thenReturn(true, false);
        assertThat(protectUtil.isUserDisabled(7L)).isTrue();
        protectUtil.enableUser(7L);
        verify(cacheStore).delete(key);
        assertThat(protectUtil.isUserDisabled(7L)).isFalse();
    }
}
