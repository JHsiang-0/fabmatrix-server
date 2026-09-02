package com.example.farm.common;

import com.example.farm.common.constant.RedisKeyConstant;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.common.utils.RedisUtil;
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
    private RedisUtil redisUtil;

    @Test
    void recordsLoginFailureWithFifteenMinuteExpiry() {
        String key = RedisKeyConstant.getKey(RedisKeyConstant.LOGIN_FAIL_COUNT, "admin");
        when(redisUtil.increment(key, 1, 15, TimeUnit.MINUTES)).thenReturn(1L);

        int count = new LoginProtectUtil(redisUtil).recordLoginFail("admin");

        assertThat(count).isEqualTo(1);
        verify(redisUtil).increment(key, 1, 15, TimeUnit.MINUTES);
    }

    @Test
    void considersOnlyFiveOrMoreFailuresLocked() {
        String key = RedisKeyConstant.getKey(RedisKeyConstant.LOGIN_FAIL_COUNT, "admin");
        when(redisUtil.getString(key)).thenReturn("5");
        assertThat(new LoginProtectUtil(redisUtil).isLocked("admin")).isTrue();

        when(redisUtil.getString(key)).thenReturn("bad-value");
        assertThat(new LoginProtectUtil(redisUtil).isLocked("admin")).isFalse();
    }

    @Test
    void readsRemainingLockTimeAndClearsFailureRecord() {
        String key = RedisKeyConstant.getKey(RedisKeyConstant.LOGIN_FAIL_COUNT, "admin");
        when(redisUtil.getString(key)).thenReturn("5");
        when(redisUtil.getExpire(key, TimeUnit.MINUTES)).thenReturn(9L);

        LoginProtectUtil protectUtil = new LoginProtectUtil(redisUtil);

        assertThat(protectUtil.getRemainingLockTime("admin")).isEqualTo(9L);
        protectUtil.clearLoginFail("admin");
        verify(redisUtil).delete(key);
    }

    @Test
    void disableAndEnableUserUseDedicatedRedisFlag() {
        String key = RedisKeyConstant.getKey(RedisKeyConstant.USER_DISABLED, 7L);
        LoginProtectUtil protectUtil = new LoginProtectUtil(redisUtil);

        protectUtil.disableUser(7L);
        verify(redisUtil).setString(key, "1");

        when(redisUtil.hasKey(key)).thenReturn(true, false);
        assertThat(protectUtil.isUserDisabled(7L)).isTrue();
        protectUtil.enableUser(7L);
        verify(redisUtil).delete(key);
        assertThat(protectUtil.isUserDisabled(7L)).isFalse();
    }
}
