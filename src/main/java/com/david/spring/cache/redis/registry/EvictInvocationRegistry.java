package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.registry.records.Key;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 注册 (cacheName, key) -> EvictInvocation 的映射，并提供细粒度的 Key 级锁。
 * 便于在需要时关联驱逐调用上下文与并发控制（对称于
 * CacheInvocationRegistry）。
 */
@Slf4j
@Component
public class EvictInvocationRegistry {

    private final ConcurrentMap<Key, EvictInvocation> invocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public void register(String cacheName, Object key, EvictInvocation invocation) {
        if (cacheName == null || invocation == null) {
            log.warn(
                    "Skip registering evict invocation due to null argument(s): cacheName={}, key={}, invocationIsNull={}",
                    cacheName,
                    key,
                    invocation == null);
            return;
        }
        // 允许 key 为空（例如 allEntries=true 的场景，以通配符表示）
        Object normalizedKey = (key == null ? "*" : key);
        invocations.put(new Key(cacheName, normalizedKey), invocation);
        log.debug("Registered evict invocation for cacheName={} key={}", cacheName, normalizedKey);
    }

    public Optional<EvictInvocation> get(String cacheName, Object key) {
        if (cacheName == null) {
            log.warn("Skip getting evict invocation due to null cacheName");
            return Optional.empty();
        }
        Object normalizedKey = (key == null ? "*" : key);
        EvictInvocation invocation = invocations.get(new Key(cacheName, normalizedKey));
        log.debug(
                "Lookup evict invocation for cacheName={} key={} -> {}",
                cacheName,
                normalizedKey,
                invocation != null ? "HIT" : "MISS");
        return Optional.ofNullable(invocation);
    }

    public ReentrantLock obtainLock(String cacheName, Object key) {
        Object normalizedKey = (key == null ? "*" : key);
        ReentrantLock lock = keyLocks.computeIfAbsent(new Key(cacheName, normalizedKey), k -> new ReentrantLock());
        log.debug("Obtained evict lock for cacheName={} key={}", cacheName, normalizedKey);
        return lock;
    }

    public void remove(String cacheName, Object key) {
        Object normalizedKey = (key == null ? "*" : key);
        Key k = new Key(cacheName, normalizedKey);
        EvictInvocation removed = invocations.remove(k);
        ReentrantLock lockRemoved = keyLocks.remove(k);
        log.debug(
                "Removed evict registry entries for cacheName={} key={} (invocationRemoved={}, lockRemoved={})",
                cacheName,
                normalizedKey,
                removed != null,
                lockRemoved != null);
    }

    /** 按 cacheName 批量清理注册信息与本地锁。 */
    public void removeAll(String cacheName) {
        if (cacheName == null) {
            log.warn("Skip removeAll evict registry due to null cacheName");
            return;
        }
        log.info("Removing all evict registry entries and locks for cacheName={}", cacheName);
        invocations.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
        keyLocks.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
        log.debug("Completed evict removeAll for cacheName={}", cacheName);
    }
}
