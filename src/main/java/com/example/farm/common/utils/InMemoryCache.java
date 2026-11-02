package com.example.farm.common.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 进程内缓存和锁，适用于单实例。
 * 缓存不跨进程共享。
 */
@Component
public class InMemoryCache implements CacheStore {
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Long> expirations = new ConcurrentHashMap<>();
    private final Map<String, List<Object>> lists = new ConcurrentHashMap<>();
    private final Map<String, Set<Object>> sets = new ConcurrentHashMap<>();
    private final Map<String, Map<Object, Double>> sortedSets = new ConcurrentHashMap<>();



    @Override
    public synchronized void set(String key, Object value) { put(key, value, 0); }

    @Override
    public synchronized void set(String key, Object value, long timeout, TimeUnit unit) {
        put(key, value, unit.toMillis(timeout));
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T> T get(String key, Class<T> clazz) {
        Object value = value(key);
        return value == null ? null : (T) value;
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T> T get(String key, TypeReference<T> typeReference) {
        return (T) value(key);
    }

    @Override
    public synchronized String getString(String key) {
        Object value = value(key);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public synchronized void setString(String key, String value) { put(key, value, 0); }

    @Override
    public synchronized void setString(String key, String value, long timeout, TimeUnit unit) {
        put(key, value, unit.toMillis(timeout));
    }

    @Override
    public synchronized void delete(String key) {
        values.remove(key); expirations.remove(key); lists.remove(key); sets.remove(key); sortedSets.remove(key);
    }

    @Override
    public synchronized void delete(Collection<String> keys) { keys.forEach(this::delete); }

    @Override
    public synchronized boolean expire(String key, long timeout, TimeUnit unit) {
        if (!hasKey(key)) return false;
        expirations.put(key, System.currentTimeMillis() + unit.toMillis(timeout));
        return true;
    }

    @Override
    public synchronized Long getExpire(String key, TimeUnit unit) {
        if (!hasKey(key)) return -2L;
        Long expires = expirations.get(key);
        return expires == null ? -1L : unit.convert(Math.max(0, expires - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized boolean hasKey(String key) { return value(key) != null || lists.containsKey(key) || sets.containsKey(key) || sortedSets.containsKey(key); }

    @Override
    public synchronized Long increment(String key, long delta) {
        synchronized (values) {
            long next = value(key) == null ? delta : Long.parseLong(String.valueOf(value(key))) + delta;
            values.put(key, next);
            return next;
        }
    }

    @Override
    public synchronized Long increment(String key, long delta, long timeout, TimeUnit unit) {
        synchronized (values) {
            boolean isNew = value(key) == null;
            Long next = increment(key, delta);
            if (isNew) expire(key, timeout, unit);
            return next;
        }
    }

    @Override
    public synchronized boolean tryLock(String key, String value, long timeout, TimeUnit unit) {
        synchronized (values) {
            if (hasKey(key)) return false;
            put(key, value, unit.toMillis(timeout));
            return true;
        }
    }

    @Override
    public synchronized void unlock(String key) { delete(key); }

    @Override
    public synchronized boolean renewLockIfOwned(String key, String value, long timeout, TimeUnit unit) {
        synchronized (values) {
            if (!value.equals(getString(key))) return false;
            expirations.put(key, System.currentTimeMillis() + unit.toMillis(timeout));
            return true;
        }
    }

    @Override
    public synchronized boolean releaseLockIfOwned(String key, String value) {
        synchronized (values) {
            if (!value.equals(getString(key))) return false;
            delete(key);
            return true;
        }
    }

    @Override
    public synchronized void listLeftPush(String key, Object value) {
        synchronized (values) { value(key); lists.computeIfAbsent(key, ignored -> new ArrayList<>()).add(0, value); }
    }

    @Override
    public synchronized void listLeftPush(String key, Object value, long timeout, TimeUnit unit) {
        listLeftPush(key, value); expire(key, timeout, unit);
    }

    @Override
    public synchronized <T> List<T> listRange(String key, long start, long end, Class<T> clazz) {
        synchronized (values) {
            value(key);
            List<Object> list = lists.getOrDefault(key, List.of());
            int from = (int) Math.max(0, start);
            int to = (int) Math.min(list.size(), end + 1);
            return from >= to ? new ArrayList<>() : list.subList(from, to).stream().map(clazz::cast).toList();
        }
    }

    @Override
    public synchronized void listTrim(String key, long start, long end) {
        synchronized (values) {
            value(key);
            List<Object> list = lists.get(key);
            if (list != null) {
                int from = (int) Math.min(list.size(), Math.max(0, start));
                int to = (int) Math.min(list.size(), Math.max(from, end + 1));
                var kept = new ArrayList<>(list.subList(from, to));
                list.clear();
                list.addAll(kept);
            }
        }
    }

    @Override
    public synchronized Long listSize(String key) { synchronized (values) { value(key); return (long) lists.getOrDefault(key, List.of()).size(); } }

    @Override
    public synchronized void setAdd(String key, Object... members) { value(key); sets.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).addAll(List.of(members)); }

    @Override
    public synchronized void setRemove(String key, Object... members) { value(key); Set<Object> set = sets.get(key); if (set != null) set.removeAll(List.of(members)); }

    @Override
    public synchronized Long setSize(String key) { value(key); return (long) sets.getOrDefault(key, Set.of()).size(); }

    @Override
    public synchronized boolean setIsMember(String key, Object member) { value(key); return sets.getOrDefault(key, Set.of()).contains(member); }

    @Override
    public synchronized void setDelete(String key) { delete(key); }

    @Override
    public synchronized void zSetAdd(String key, Object member, double score) { value(key); sortedSets.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(member, score); }

    @Override
    public synchronized void zSetRemove(String key, Object... members) { value(key); Map<Object, Double> set = sortedSets.get(key); if (set != null) for (Object member : members) set.remove(member); }

    @Override
    public synchronized Long zSetSize(String key) { value(key); return (long) sortedSets.getOrDefault(key, Map.of()).size(); }

    @Override
    public synchronized Long zSetRemoveRangeByScore(String key, double minScore, double maxScore) {
        value(key);
        Map<Object, Double> set = sortedSets.get(key);
        if (set == null) return 0L;
        long removed = 0;
        for (var entry : set.entrySet()) {
            if (entry.getValue() >= minScore && entry.getValue() <= maxScore && set.remove(entry.getKey(), entry.getValue())) removed++;
        }
        return removed;
    }

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
