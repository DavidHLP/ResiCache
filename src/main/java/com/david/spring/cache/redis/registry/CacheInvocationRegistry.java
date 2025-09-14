package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.reflect.CachedInvocation;

import jakarta.annotation.Nonnull;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** 注册 (cacheName, key) -> CachedInvocation 的映射，并提供细粒度的 Key 级锁， 以支撑“即将到期主动刷新缓存”的并发控制。 */
@Slf4j
@Component
public class CacheInvocationRegistry {

    private final ConcurrentMap<Key, CachedInvocation> invocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public void register(String cacheName, Object key, CachedInvocation invocation) {
        if (cacheName == null || key == null || invocation == null) {
            return;
        }
        invocations.put(new Key(cacheName, key), invocation);
    }

    public Optional<CachedInvocation> get(String cacheName, Object key) {
        if (cacheName == null || key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(invocations.get(new Key(cacheName, key)));
    }

    public ReentrantLock obtainLock(String cacheName, Object key) {
        return keyLocks.computeIfAbsent(new Key(cacheName, key), k -> new ReentrantLock());
    }

    public void remove(String cacheName, Object key) {
        Key k = new Key(cacheName, key);
        invocations.remove(k);
        keyLocks.remove(k);
    }

    /**
     * 按 cacheName 批量清理注册信息与本地锁。
     */
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
