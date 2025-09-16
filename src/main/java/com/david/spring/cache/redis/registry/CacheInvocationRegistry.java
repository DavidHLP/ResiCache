package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.records.Key;

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
            log.warn(
                    "Skip registering invocation due to null argument(s): cacheName={}, key={}, invocationIsNull={}",
                    cacheName,
                    key,
                    invocation == null);
            return;
        }
        invocations.put(new Key(cacheName, key), invocation);
        log.debug("Registered invocation for cacheName={} key={}", cacheName, key);
    }

    public Optional<CachedInvocation> get(String cacheName, Object key) {
        if (cacheName == null || key == null) {
            log.warn(
                    "Skip getting invocation due to null argument(s): cacheName={}, key={}",
                    cacheName,
                    key);
            return Optional.empty();
        }
        CachedInvocation invocation = invocations.get(new Key(cacheName, key));
        log.debug(
                "Lookup invocation for cacheName={} key={} -> {}",
                cacheName,
                key,
                invocation != null ? "HIT" : "MISS");
        return Optional.ofNullable(invocation);
    }

    public ReentrantLock obtainLock(String cacheName, Object key) {
        ReentrantLock lock =
                keyLocks.computeIfAbsent(new Key(cacheName, key), k -> new ReentrantLock());
        log.debug("Obtained lock for cacheName={} key={}", cacheName, key);
        return lock;
    }

    public void remove(String cacheName, Object key) {
        Key k = new Key(cacheName, key);
        CachedInvocation removed = invocations.remove(k);
        ReentrantLock lockRemoved = keyLocks.remove(k);
        log.debug(
                "Removed registry entries for cacheName={} key={} (invocationRemoved={}, lockRemoved={})",
                cacheName,
                key,
                removed != null,
                lockRemoved != null);
    }

    /** 按 cacheName 批量清理注册信息与本地锁。 */
    public void removeAll(String cacheName) {
        if (cacheName == null) {
            log.warn("Skip removeAll due to null cacheName");
            return;
        }
        log.info("Removing all registry entries and locks for cacheName={}", cacheName);
        invocations.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
        keyLocks.keySet().removeIf(k -> cacheName.equals(k.cacheName()));
        log.debug("Completed removeAll for cacheName={}", cacheName);
    }
}
