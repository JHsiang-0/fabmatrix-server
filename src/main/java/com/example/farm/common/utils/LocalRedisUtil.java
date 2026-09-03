package com.example.farm.common.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Local Edition 的进程内缓存/锁实现。
 * 它只适用于单个 Farm 进程，不提供 Server Edition 的跨实例一致性。
 */
@Component
@Profile("local")
public class LocalRedisUtil extends RedisUtil {
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Long> expirations = new ConcurrentHashMap<>();
    private final Map<String, List<Object>> lists = new ConcurrentHashMap<>();
    private final Map<String, Set<Object>> sets = new ConcurrentHashMap<>();

    public LocalRedisUtil() {
        super(null, null);
    }

    @Override
    public void set(String key, Object value) { put(key, value, 0); }

    @Override
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        put(key, value, unit.toMillis(timeout));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(String key, Class<T> clazz) {
        Object value = value(key);
        return value == null ? null : (T) value;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(String key, TypeReference<T> typeReference) {
        return (T) value(key);
    }

    @Override
    public String getString(String key) {
        Object value = value(key);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public void setString(String key, String value) { put(key, value, 0); }

    @Override
    public void setString(String key, String value, long timeout, TimeUnit unit) {
        put(key, value, unit.toMillis(timeout));
    }

    @Override
    public void delete(String key) {
        values.remove(key); expirations.remove(key); lists.remove(key); sets.remove(key);
    }

    @Override
    public void delete(Collection<String> keys) { keys.forEach(this::delete); }

    @Override
    public boolean expire(String key, long timeout, TimeUnit unit) {
        if (!hasKey(key)) return false;
        expirations.put(key, System.currentTimeMillis() + unit.toMillis(timeout));
        return true;
    }

    @Override
    public Long getExpire(String key, TimeUnit unit) {
        if (!hasKey(key)) return -2L;
        Long expires = expirations.get(key);
        return expires == null ? -1L : unit.convert(Math.max(0, expires - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean hasKey(String key) { return value(key) != null || lists.containsKey(key) || sets.containsKey(key); }

    @Override
    public Long increment(String key, long delta) {
        synchronized (values) {
            long next = value(key) == null ? delta : Long.parseLong(String.valueOf(value(key))) + delta;
            put(key, next, 0);
            return next;
        }
    }

    @Override
    public Long increment(String key, long delta, long timeout, TimeUnit unit) {
        Long next = increment(key, delta);
        if (next == delta) expire(key, timeout, unit);
        return next;
    }

    @Override
    public boolean tryLock(String key, String value, long timeout, TimeUnit unit) {
        synchronized (values) {
            if (hasKey(key)) return false;
            put(key, value, unit.toMillis(timeout));
            return true;
        }
    }

    @Override
    public void unlock(String key) { delete(key); }

    @Override
    public void listLeftPush(String key, Object value) {
        lists.computeIfAbsent(key, ignored -> new ArrayList<>()).add(0, value);
    }

    @Override
    public void listLeftPush(String key, Object value, long timeout, TimeUnit unit) {
        listLeftPush(key, value); expire(key, timeout, unit);
    }

    @Override
    public <T> List<T> listRange(String key, long start, long end, Class<T> clazz) {
        List<Object> list = lists.getOrDefault(key, List.of());
        int from = (int) Math.max(0, start);
        int to = (int) Math.min(list.size(), end + 1);
        return from >= to ? new ArrayList<>() : list.subList(from, to).stream().map(clazz::cast).toList();
    }

    @Override
    public void listTrim(String key, long start, long end) {
        List<Object> list = lists.get(key);
        if (list != null) list.subList((int) Math.min(list.size(), end + 1), list.size()).clear();
    }

    @Override
    public Long listSize(String key) { return (long) lists.getOrDefault(key, List.of()).size(); }

    @Override
    public void setAdd(String key, Object... members) { sets.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).addAll(List.of(members)); }

    @Override
    public void setRemove(String key, Object... members) { Set<Object> set = sets.get(key); if (set != null) set.removeAll(List.of(members)); }

    @Override
    public Long setSize(String key) { return (long) sets.getOrDefault(key, Set.of()).size(); }

    @Override
    public boolean setIsMember(String key, Object member) { return sets.getOrDefault(key, Set.of()).contains(member); }

    @Override
    public void setDelete(String key) { delete(key); }

    @Override
    public void zSetAdd(String key, Object member, double score) { setAdd(key, member); }

    @Override
    public void zSetRemove(String key, Object... members) { setRemove(key, members); }

    @Override
    public Long zSetSize(String key) { return setSize(key); }

    @Override
    public Long zSetRemoveRangeByScore(String key, double minScore, double maxScore) { return 0L; }

    private Object value(String key) {
        Long expires = expirations.get(key);
        if (expires != null && expires <= System.currentTimeMillis()) { delete(key); return null; }
        return values.get(key);
    }

    private void put(String key, Object value, long ttlMillis) {
        values.put(key, value);
        if (ttlMillis > 0) expirations.put(key, System.currentTimeMillis() + ttlMillis);
        else expirations.remove(key);
    }
}
