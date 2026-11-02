package com.example.farm.common.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 单进程运行时缓存与互斥锁。 */
public interface CacheStore {
    void set(String key, Object value);
    void set(String key, Object value, long timeout, TimeUnit unit);
    <T> T get(String key, Class<T> clazz);
    <T> T get(String key, TypeReference<T> typeReference);
    String getString(String key);
    void setString(String key, String value);
    void setString(String key, String value, long timeout, TimeUnit unit);
    void delete(String key);
    void delete(Collection<String> keys);
    boolean expire(String key, long timeout, TimeUnit unit);
    Long getExpire(String key, TimeUnit unit);
    boolean hasKey(String key);
    Long increment(String key, long delta);
    Long increment(String key, long delta, long timeout, TimeUnit unit);
    boolean tryLock(String key, String value, long timeout, TimeUnit unit);
    void unlock(String key);
    boolean renewLockIfOwned(String key, String value, long timeout, TimeUnit unit);
    boolean releaseLockIfOwned(String key, String value);
    void listLeftPush(String key, Object value);
    void listLeftPush(String key, Object value, long timeout, TimeUnit unit);
    <T> List<T> listRange(String key, long start, long end, Class<T> clazz);
    void listTrim(String key, long start, long end);
    Long listSize(String key);
    void setAdd(String key, Object... members);
    void setRemove(String key, Object... members);
    Long setSize(String key);
    boolean setIsMember(String key, Object member);
    void setDelete(String key);
    void zSetAdd(String key, Object member, double score);
    void zSetRemove(String key, Object... members);
    Long zSetSize(String key);
    Long zSetRemoveRangeByScore(String key, double minScore, double maxScore);
    default String getLockValue(String key) { return getString(key); }
}
