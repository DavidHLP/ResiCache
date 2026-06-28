package io.github.davidhlp.spring.cache.redis.integration;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test service for annotation-level cache integration tests.
 * Each method is designed to verify a specific Spring Cache contract behavior.
 */
@Service
public class TestCacheService {

    private final AtomicInteger callCount = new AtomicInteger(0);

    public void reset() {
        callCount.set(0);
    }

    public int getCallCount() {
        return callCount.get();
    }

    @RedisCacheable(cacheNames = "testCache", key = "#id")
    public String getById(Long id) {
        callCount.incrementAndGet();
        return "value-" + id;
    }

    @RedisCacheable(cacheNames = "testCache", key = "#id", ttl = 120)
    public String getByIdWithTtl(Long id) {
        callCount.incrementAndGet();
        return "ttl-value-" + id;
    }

    @RedisCacheable(cacheNames = "testCache", key = "#id", condition = "#id > 0")
    public String getByIdWithCondition(Long id) {
        callCount.incrementAndGet();
        return "conditional-" + id;
    }

    @RedisCacheable(cacheNames = "testCache", key = "#id", unless = "#result == null")
    public String getByIdWithUnless(Long id) {
        callCount.incrementAndGet();
        return id > 0 ? "unless-value-" + id : null;
    }

    @RedisCachePut(cacheNames = "testCache", key = "#id")
    public String putById(Long id, String value) {
        callCount.incrementAndGet();
        return value;
    }

    @RedisCacheEvict(cacheNames = "testCache", key = "#id")
    public void evictById(Long id) {
        callCount.incrementAndGet();
    }

    @RedisCacheEvict(cacheNames = "testCache", allEntries = true)
    public void evictAll() {
        callCount.incrementAndGet();
    }

    @RedisCaching(
            redisCacheable = {
                    @RedisCacheable(cacheNames = "cache1", key = "#id"),
                    @RedisCacheable(cacheNames = "cache2", key = "#id")
            },
            redisCacheEvict = {
                    @RedisCacheEvict(cacheNames = "cache3", key = "#id")
            }
    )
    public String multiCacheOperation(Long id) {
        callCount.incrementAndGet();
        return "multi-" + id;
    }

    @RedisCacheable(cacheNames = "testCache", key = "#id", cacheNullValues = true)
    public String getByIdWithNullCache(Long id) {
        callCount.incrementAndGet();
        return id > 0 ? "null-cache-value-" + id : null;
    }

    @RedisCacheable(cacheNames = "testCache", key = "#id", useBloomFilter = true)
    public String getByIdWithBloomFilter(Long id) {
        callCount.incrementAndGet();
        return "bloom-value-" + id;
    }

    @RedisCacheable(cacheNames = "testCache", key = "#id", sync = true)
    public String getByIdWithSync(Long id) {
        callCount.incrementAndGet();
        return "sync-value-" + id;
    }

    /**
     * 纯 Spring 原生 {@link Cacheable} —— 不带任何 ResiCache 特性(useBloomFilter/sync/ttl)。
     * 用于 Path C Step 0 契约测试: 验证 ResiCache 链对 Spring 原生 @Cacheable 也正常工作
     * (Step 3 引入 ResiCacheMethodInterceptor 后,纯 @Cacheable 仍应通过 ResiCache
     * CacheManager 走链,而不是被绕开)。
     */
    @Cacheable(cacheNames = "testCache", key = "#id")
    public String getByIdWithPureSpring(Long id) {
        callCount.incrementAndGet();
        return "pure-" + id;
    }
}
