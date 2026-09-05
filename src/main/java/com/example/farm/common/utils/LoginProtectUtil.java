package com.example.farm.common.utils;

import com.example.farm.common.constant.RedisKeyConstant;
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

    private final RedisUtil redisUtil;
    private final UserMapper userMapper;

    public LoginProtectUtil(RedisUtil redisUtil) {
        this(redisUtil, null);
    }

    @Autowired
    public LoginProtectUtil(RedisUtil redisUtil, UserMapper userMapper) {
        this.redisUtil = redisUtil;
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
        String key = RedisKeyConstant.getKey(RedisKeyConstant.LOGIN_FAIL_COUNT, username);
        Long count = redisUtil.increment(key, 1, LOCK_TIME_MINUTES, TimeUnit.MINUTES);
        
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
        String key = RedisKeyConstant.getKey(RedisKeyConstant.LOGIN_FAIL_COUNT, username);
        String countStr = redisUtil.getString(key);
        
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
        
        String key = RedisKeyConstant.getKey(RedisKeyConstant.LOGIN_FAIL_COUNT, username);
        Long expire = redisUtil.getExpire(key, TimeUnit.MINUTES);
        return expire != null ? expire : LOCK_TIME_MINUTES;
    }

    /**
     * 清除登录失败记录（登录成功时调用）
     *
     * @param username 用户名
     */
    public void clearLoginFail(String username) {
        String key = RedisKeyConstant.getKey(RedisKeyConstant.LOGIN_FAIL_COUNT, username);
        redisUtil.delete(key);
        log.debug("清除用户 [{}] 的登录失败记录", username);
    }

    /**
     * 获取当前失败次数
     *
     * @param username 用户名
     * @return 失败次数
     */
    public int getFailCount(String username) {
        String key = RedisKeyConstant.getKey(RedisKeyConstant.LOGIN_FAIL_COUNT, username);
        String countStr = redisUtil.getString(key);
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
        String key = RedisKeyConstant.getKey(RedisKeyConstant.USER_DISABLED, userId);
        try {
            redisUtil.setString(key, "1");
        } catch (RuntimeException exception) {
            log.warn("用户禁用缓存失效失败，数据库状态仍作为真源: userId={}", userId, exception);
        }
    }

    /**
     * 取消用户禁用状态。
     */
    public void enableUser(Long userId) {
        String key = RedisKeyConstant.getKey(RedisKeyConstant.USER_DISABLED, userId);
        try {
            redisUtil.delete(key);
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
        String key = RedisKeyConstant.getKey(RedisKeyConstant.USER_DISABLED, userId);
        return redisUtil.hasKey(key);
    }
}
