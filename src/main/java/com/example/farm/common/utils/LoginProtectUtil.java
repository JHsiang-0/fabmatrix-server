package com.example.farm.common.utils;

import com.example.farm.common.constant.CacheKeyConstant;
import com.example.farm.entity.User;
import com.example.farm.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

/**
 * 登录保护工具类
 * 防止暴力破解密码
 */
@Slf4j
@Component
public class LoginProtectUtil {

    private final CacheStore cacheStore;
    private final UserMapper userMapper;

    public LoginProtectUtil(CacheStore cacheStore) {
        this(cacheStore, null);
    }

    @Autowired
    public LoginProtectUtil(CacheStore cacheStore, UserMapper userMapper) {
        this.cacheStore = cacheStore;
        this.userMapper = userMapper;
    }

    // 最大允许失败次数
    private static final int MAX_FAIL_COUNT = 5;
    // 锁定时间：15分钟
    private static final long LOCK_TIME_MINUTES = 15;

    /**
     * 记录登录失败
     *
     * @param username 用户名
     * @return 当前失败次数
     */
    public int recordLoginFail(String username) {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.LOGIN_FAIL_COUNT, username);
        Long count = cacheStore.increment(key, 1, LOCK_TIME_MINUTES, TimeUnit.MINUTES);

        int failCount = count != null ? count.intValue() : 1;

        if (failCount >= MAX_FAIL_COUNT) {
            log.warn("用户登录失败次数过多，账号已锁定: username={}, 锁定时长={}分钟", username, LOCK_TIME_MINUTES);
        }

        return failCount;
    }

    /**
     * 检查账号是否被锁定
     *
     * @param username 用户名
     * @return true-已锁定
     */
    public boolean isLocked(String username) {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.LOGIN_FAIL_COUNT, username);
        String countStr = cacheStore.getString(key);

        if (countStr == null) {
            return false;
        }

        try {
            int count = Integer.parseInt(countStr);
            return count >= MAX_FAIL_COUNT;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 获取剩余锁定时间（分钟）
     *
     * @param username 用户名
     * @return 剩余分钟数，0表示未锁定
     */
    public long getRemainingLockTime(String username) {
        if (!isLocked(username)) {
            return 0;
        }

        String key = CacheKeyConstant.getKey(CacheKeyConstant.LOGIN_FAIL_COUNT, username);
        Long expire = cacheStore.getExpire(key, TimeUnit.MINUTES);
        return expire != null ? expire : LOCK_TIME_MINUTES;
    }

    /**
     * 清除登录失败记录（登录成功时调用）
     *
     * @param username 用户名
     */
    public void clearLoginFail(String username) {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.LOGIN_FAIL_COUNT, username);
        cacheStore.delete(key);
        log.debug("清除用户 [{}] 的登录失败记录", username);
    }

    /**
     * 获取当前失败次数
     *
     * @param username 用户名
     * @return 失败次数
     */
    public int getFailCount(String username) {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.LOGIN_FAIL_COUNT, username);
        String countStr = cacheStore.getString(key);
        if (countStr == null) {
            return 0;
        }
        try {
            return Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 标记用户为禁用状态（长期生效，直到显式恢复）。
     */
    public void disableUser(Long userId) {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.USER_DISABLED, userId);
        try {
            cacheStore.setString(key, "1");
        } catch (RuntimeException exception) {
            log.warn("用户禁用缓存失效失败，数据库状态仍作为真源: userId={}", userId, exception);
        }
    }

    /**
     * 取消用户禁用状态。
     */
    public void enableUser(Long userId) {
        String key = CacheKeyConstant.getKey(CacheKeyConstant.USER_DISABLED, userId);
        try {
            cacheStore.delete(key);
        } catch (RuntimeException exception) {
            log.warn("用户启用缓存失效失败，数据库状态仍作为真源: userId={}", userId, exception);
        }
    }

    /**
     * 检查用户是否已被禁用。
     */
    public boolean isUserDisabled(Long userId) {
        if (userMapper != null) {
            User user = userMapper.selectById(userId);
            return user == null || Boolean.FALSE.equals(user.getEnabled());
        }
        String key = CacheKeyConstant.getKey(CacheKeyConstant.USER_DISABLED, userId);
        return cacheStore.hasKey(key);
    }
}
