package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.locks.DistributedLock;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 受保护的缓存策略实现
 * 提供缓存雪崩、穿透、击穿等保护机制
 *
 * @author David
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProtectedCacheableStrategy implements CacheableStrategy<Object>, Ordered {

    private static final String STRATEGY_NAME = "ProtectedCacheableStrategy";
    private static final int PRIORITY = Ordered.HIGHEST_PRECEDENCE + 100;

    private final CacheAvalanche cacheAvalanche;
    private final CachePenetration cachePenetration;
    private final DistributedLock distributedLock;

    @Override
    @Nullable
    public Cache.ValueWrapper get(@NonNull Cache cache, @NonNull Object key, @NonNull CachedInvocation cachedInvocation) {
        log.debug("Getting cache value using protected strategy, cache={}, key={}", cache.getName(), key);

        // 检查是否需要布隆过滤器保护
        if (needsBloomFilterProtection(cachedInvocation)) {
            if (!cachePenetration.mightContain(cache.getName(), key.toString())) {
                log.debug("Bloom filter rejected key: {}", key);
                return null;
            }
        }

        if (cache instanceof RedisProCache redisProCache) {
            return redisProCache.getFromParent(key);
        }
        return cache.get(key);
    }

    @Override
    @NonNull
    public Object get(@NonNull Cache cache, @NonNull Object key, @NonNull Callable<Object> valueLoader, @NonNull CachedInvocation cachedInvocation) {
        log.debug("Getting cache value with loader using protected strategy, cache={}, key={}", cache.getName(), key);

        // 检查是否需要分布式锁保护
        if (needsDistributedLockProtection(cachedInvocation)) {
            String lockKey = buildLockKey(cache.getName(), key);
            try {
                if (distributedLock.tryLock(lockKey, 30, 10, TimeUnit.SECONDS)) {
                    try {
                        return executeWithProtection(cache, key, valueLoader, cachedInvocation);
                    } finally {
                        distributedLock.unlock(lockKey);
                    }
                } else {
                    log.warn("Failed to acquire distributed lock for key: {}", key);
                    // 如果获取锁失败，降级到普通获取
                    Cache.ValueWrapper wrapper = cache.get(key);
                    if (wrapper != null) {
                        return wrapper.get();
                    }
                    throw new IllegalStateException("Unable to load cache value for key: " + key);
                }
            } catch (Exception e) {
                log.error("Error in distributed lock protection for key: {}", key, e);
                throw new RuntimeException("Cache operation failed", e);
            }
        }

        return executeWithProtection(cache, key, valueLoader, cachedInvocation);
    }

    private Object executeWithProtection(@NonNull Cache cache, @NonNull Object key, @NonNull Callable<Object> valueLoader, @NonNull CachedInvocation cachedInvocation) {
        // 首先尝试从缓存获取
        Cache.ValueWrapper existing;
        if (cache instanceof RedisProCache redisProCache) {
            existing = redisProCache.getFromParent(key);
        } else {
            existing = cache.get(key);
        }
        if (existing != null) {
            return existing.get();
        }

        // 加载新值
        Object value;
        try {
            value = valueLoader.call();
        } catch (Exception e) {
            throw new RuntimeException("Value loader failed", e);
        }

        // 应用雪崩保护进行存储
        if (needsAvalancheProtection(cachedInvocation) && value != null) {
            long baseTtl = cachedInvocation.getCachedInvocationContext().ttl();
            long jitteredTtl = cacheAvalanche.jitterTtlSeconds(baseTtl);
            log.debug("Applied avalanche protection: baseTtl={}, jitteredTtl={}", baseTtl, jitteredTtl);
            cache.put(key, value);
        } else {
            cache.put(key, value);
        }

        // 更新布隆过滤器
        if (needsBloomFilterProtection(cachedInvocation) && value != null) {
            cachePenetration.addIfEnabled(cache.getName(), key.toString());
        }

        return value;
    }

    @Override
    public void put(@NonNull Cache cache, @NonNull Object key, @Nullable Object value, @NonNull CachedInvocation cachedInvocation) {
        log.debug("Putting cache value using protected strategy, cache={}, key={}", cache.getName(), key);

        cache.put(key, value);

        // 更新布隆过滤器
        if (needsBloomFilterProtection(cachedInvocation) && value != null) {
            cachePenetration.addIfEnabled(cache.getName(), key.toString());
        }
    }

    @Override
    @NonNull
    public Cache.ValueWrapper putIfAbsent(@NonNull Cache cache, @NonNull Object key, @Nullable Object value, @NonNull CachedInvocation cachedInvocation) {
        log.debug("Putting cache value if absent using protected strategy, cache={}, key={}", cache.getName(), key);

        Cache.ValueWrapper result = cache.putIfAbsent(key, value);

        // 更新布隆过滤器
        if (needsBloomFilterProtection(cachedInvocation) && value != null) {
            cachePenetration.addIfEnabled(cache.getName(), key.toString());
        }

        return result;
    }

    @Override
    public void evict(@NonNull Cache cache, @NonNull Object key, @NonNull CachedInvocation cachedInvocation) {
        log.debug("Evicting cache value using protected strategy, cache={}, key={}", cache.getName(), key);
        cache.evict(key);
    }

    @Override
    public void clear(@NonNull Cache cache, @NonNull CachedInvocation cachedInvocation) {
        log.debug("Clearing cache using protected strategy, cache={}", cache.getName());
        cache.clear();
    }

    @Override
    public boolean supports(@NonNull CachedInvocation cachedInvocation) {
        return cachedInvocation.hasProtectionConfig() ||
               needsAvalancheProtection(cachedInvocation) ||
               needsBloomFilterProtection(cachedInvocation);
    }

    @Override
    @NonNull
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public int getOrder() {
        return PRIORITY;
    }

    private boolean needsDistributedLockProtection(@NonNull CachedInvocation cachedInvocation) {
        return cachedInvocation.getCachedInvocationContext() != null &&
               cachedInvocation.getCachedInvocationContext().protectionConfig() != null &&
               cachedInvocation.getCachedInvocationContext().protectionConfig().distributedLock();
    }

    private boolean needsBloomFilterProtection(@NonNull CachedInvocation cachedInvocation) {
        return cachedInvocation.getCachedInvocationContext() != null &&
               cachedInvocation.getCachedInvocationContext().protectionConfig() != null &&
               cachedInvocation.getCachedInvocationContext().protectionConfig().useBloomFilter();
    }

    private boolean needsAvalancheProtection(@NonNull CachedInvocation cachedInvocation) {
        return cachedInvocation.getCachedInvocationContext() != null &&
               cachedInvocation.getCachedInvocationContext().ttlConfig() != null &&
               cachedInvocation.getCachedInvocationContext().ttlConfig().randomTtl();
    }

    private String buildLockKey(@NonNull String cacheName, @NonNull Object key) {
        return "cache:lock:" + cacheName + ":" + key;
    }
}