package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.reflect.EvictInvocation;

import jakarta.annotation.Nonnull;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 注册 (cacheName, key) -> EvictInvocation 的映射，并提供细粒度的 Key 级锁。 便于在需要时关联驱逐调用上下文与并发控制（对称于
 * CacheInvocationRegistry）。
 */
@Slf4j
@Component
public class EvictInvocationRegistry {

    private final ConcurrentMap<Key, EvictInvocation> invocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public void register(String cacheName, Object key, EvictInvocation invocation) {
        if (cacheName == null || invocation == null) {
            return;
        }
        // 允许 key 为空（例如 allEntries=true 的场景，以通配符表示）
        Object normalizedKey = (key == null ? "*" : key);
        invocations.put(new Key(cacheName, normalizedKey), invocation);
    }

    public Optional<EvictInvocation> get(String cacheName, Object key) {
        if (cacheName == null) {
            return Optional.empty();
        }
        Object normalizedKey = (key == null ? "*" : key);
        return Optional.ofNullable(invocations.get(new Key(cacheName, normalizedKey)));
    }

    public ReentrantLock obtainLock(String cacheName, Object key) {
        Object normalizedKey = (key == null ? "*" : key);
        return keyLocks.computeIfAbsent(
                new Key(cacheName, normalizedKey), k -> new ReentrantLock());
    }

    public void remove(String cacheName, Object key) {
        Object normalizedKey = (key == null ? "*" : key);
        Key k = new Key(cacheName, normalizedKey);
        invocations.remove(k);
        keyLocks.remove(k);
    }

    /** 按 cacheName 批量清理注册信息与本地锁。 */
    public void removeAll(String cacheName) {
        if (cacheName == null) return;
        invocations.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
        keyLocks.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
    }

    private record Key(String cacheName, Object key) {

        @Override
        @Nonnull
        public String toString() {
            return cacheName + "::" + key;
        }
    }
}
