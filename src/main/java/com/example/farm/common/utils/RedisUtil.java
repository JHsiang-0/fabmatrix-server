package com.example.farm.common.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Collections;

/**
 * Redis 工具类
 * 使用 String 存储，手动进行 JSON 序列化
 */
@Slf4j
@Component
@Profile("!local")
public class RedisUtil {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisUtil(RedisTemplate<String, String> redisTemplate,
                     @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== 基础操作 ====================

    public void set(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json);
        } catch (JsonProcessingException e) {
            log.error("Redis 写入失败: key={}, 错误={}", key, e.getMessage());
        }
    }

    public void set(String key, Object value, long timeout, TimeUnit unit) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, timeout, unit);
        } catch (JsonProcessingException e) {
            log.error("Redis 写入失败: key={}, 错误={}", key, e.getMessage());
        }
    }

    public <T> T get(String key, Class<T> clazz) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("Redis 读取失败: key={}, 错误={}", key, e.getMessage());
            return null;
        }
    }

    public <T> T get(String key, TypeReference<T> typeReference) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            log.error("Redis 读取失败: key={}, 错误={}", key, e.getMessage());
            return null;
        }
    }

    public String getString(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public void setString(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void setString(String key, String value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public void delete(Collection<String> keys) {
        redisTemplate.delete(keys);
    }

    public boolean expire(String key, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, timeout, unit));
    }

    public Long getExpire(String key, TimeUnit unit) {
        return redisTemplate.getExpire(key, unit);
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    public Long increment(String key, long delta, long timeout, TimeUnit unit) {
        Long value = redisTemplate.opsForValue().increment(key, delta);
        if (value != null && value == delta) {
            expire(key, timeout, unit);
        }
        return value;
    }

    // ==================== 分布式锁 ====================

    public boolean tryLock(String key, String value, long timeout, TimeUnit unit) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
        return Boolean.TRUE.equals(success);
    }

    public void unlock(String key) {
        delete(key);
    }

    public String getLockValue(String key) {
        return getString(key);
    }

    /** 仅当锁值仍属于当前持有者时续租，避免续租已被接管的锁。 */
    public boolean renewLockIfOwned(String key, String value, long timeout, TimeUnit unit) {
        Long result = redisTemplate.execute(new DefaultRedisScript<>(
                        "if redis.call('get', KEYS[1]) == ARGV[1] then "
                                + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
                        Long.class),
                Collections.singletonList(key), value, String.valueOf(unit.toMillis(timeout)));
        return Long.valueOf(1L).equals(result);
    }

    /** 仅当锁值仍属于当前持有者时释放，避免删除后继请求持有的锁。 */
    public boolean releaseLockIfOwned(String key, String value) {
        Long result = redisTemplate.execute(new DefaultRedisScript<>(
                        "if redis.call('get', KEYS[1]) == ARGV[1] then "
                                + "return redis.call('del', KEYS[1]) else return 0 end",
                        Long.class),
                Collections.singletonList(key), value);
        return Long.valueOf(1L).equals(result);
    }

    // ==================== List 操作 ====================

    public void listLeftPush(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForList().leftPush(key, json);
        } catch (JsonProcessingException e) {
            log.error("Redis 列表写入失败: key={}", key, e);
        }
    }

    public void listLeftPush(String key, Object value, long timeout, TimeUnit unit) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForList().leftPush(key, json);
            expire(key, timeout, unit);
        } catch (JsonProcessingException e) {
            log.error("Redis 列表写入失败: key={}", key, e);
        }
    }

    public <T> List<T> listRange(String key, long start, long end, Class<T> clazz) {
        try {
            List<String> jsonList = redisTemplate.opsForList().range(key, start, end);
            if (jsonList == null || jsonList.isEmpty()) return new ArrayList<>();
            
            List<T> result = new ArrayList<>();
            for (String json : jsonList) {
                result.add(objectMapper.readValue(json, clazz));
            }
            return result;
        } catch (Exception e) {
            log.error("Redis 列表读取失败: key={}", key, e);
            return new ArrayList<>();
        }
    }

    public void listTrim(String key, long start, long end) {
        redisTemplate.opsForList().trim(key, start, end);
    }

    public Long listSize(String key) {
        return redisTemplate.opsForList().size(key);
    }

    // ==================== Set 操作 ====================

    public void setAdd(String key, Object... members) {
        try {
            String[] jsonMembers = new String[members.length];
            for (int i = 0; i < members.length; i++) {
                jsonMembers[i] = objectMapper.writeValueAsString(members[i]);
            }
            redisTemplate.opsForSet().add(key, jsonMembers);
        } catch (JsonProcessingException e) {
            log.error("Redis 集合添加失败: key={}", key, e);
        }
    }

    public void setRemove(String key, Object... members) {
        try {
            String[] jsonMembers = new String[members.length];
            for (int i = 0; i < members.length; i++) {
                jsonMembers[i] = objectMapper.writeValueAsString(members[i]);
            }
            redisTemplate.opsForSet().remove(key, (Object[]) jsonMembers);
        } catch (JsonProcessingException e) {
            log.error("Redis 集合删除失败: key={}", key, e);
        }
    }

    public Long setSize(String key) {
        return redisTemplate.opsForSet().size(key);
    }

    public boolean setIsMember(String key, Object member) {
        try {
            String json = objectMapper.writeValueAsString(member);
            return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, json));
        } catch (JsonProcessingException e) {
            log.error("Redis 集合成员判断失败: key={}", key, e);
            return false;
        }
    }

    public void setDelete(String key) {
        redisTemplate.delete(key);
    }

    // ==================== ZSet (Sorted Set) 操作 ====================

    public void zSetAdd(String key, Object member, double score) {
        try {
            String json = objectMapper.writeValueAsString(member);
            redisTemplate.opsForZSet().add(key, json, score);
        } catch (JsonProcessingException e) {
            log.error("Redis 有序集合添加失败: key={}", key, e);
        }
    }

    public void zSetRemove(String key, Object... members) {
        try {
            String[] jsonMembers = new String[members.length];
            for (int i = 0; i < members.length; i++) {
                jsonMembers[i] = objectMapper.writeValueAsString(members[i]);
            }
            redisTemplate.opsForZSet().remove(key, (Object[]) jsonMembers);
        } catch (JsonProcessingException e) {
            log.error("Redis 有序集合删除失败: key={}", key, e);
        }
    }

    public Long zSetCount(String key, double minScore, double maxScore) {
        return redisTemplate.opsForZSet().count(key, minScore, maxScore);
    }

    public Long zSetRemoveRangeByScore(String key, double minScore, double maxScore) {
        return redisTemplate.opsForZSet().removeRangeByScore(key, minScore, maxScore);
    }

    public Long zSetSize(String key) {
        return redisTemplate.opsForZSet().size(key);
    }

    // ==================== 发布订阅 ====================

    public void convertAndSend(String channel, Object message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, json);
        } catch (JsonProcessingException e) {
            log.error("Redis 发布消息失败: channel={}", channel, e);
        }
    }
}
