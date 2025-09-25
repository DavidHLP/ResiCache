package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AdvancedTestService {

    private final AtomicInteger distributedLockCallCount = new AtomicInteger(0);
    private final AtomicInteger internalLockCallCount = new AtomicInteger(0);
    private final AtomicInteger bloomFilterCallCount = new AtomicInteger(0);
    private final AtomicInteger preRefreshCallCount = new AtomicInteger(0);
    private final AtomicInteger randomTtlCallCount = new AtomicInteger(0);

    /**
     * 测试分布式锁功能
     */
    @RedisCacheable(
            cacheNames = "distributed-lock-cache",
            key = "#userId",
            ttl = 300,
            distributedLock = true
    )
    public String getWithDistributedLock(String userId) {
        int count = distributedLockCallCount.incrementAndGet();
        log.info("Getting data with distributed lock for user: {}, call count: {}", userId, count);

        // 模拟数据库查询延迟
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return "User data for " + userId + " (call #" + count + ")";
    }

    /**
     * 测试内部锁功能
     */
    @RedisCacheable(
            cacheNames = "internal-lock-cache",
            key = "#productId",
            ttl = 180,
            internalLock = true
    )
    public String getWithInternalLock(String productId) {
        int count = internalLockCallCount.incrementAndGet();
        log.info("Getting product with internal lock for id: {}, call count: {}", productId, count);

        // 模拟数据库查询延迟
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return "Product " + productId + " (call #" + count + ")";
    }

    /**
     * 测试布隆过滤器功能
     */
    @RedisCacheable(
            cacheNames = "bloom-filter-cache",
            key = "#itemId",
            ttl = 600,
            useBloomFilter = true
    )
    public String getWithBloomFilter(String itemId) {
        int count = bloomFilterCallCount.incrementAndGet();
        log.info("Getting item with bloom filter for id: {}, call count: {}", itemId, count);

        // 模拟某些ID不存在的情况
        if ("nonexistent".equals(itemId)) {
            return null;
        }

        return "Item " + itemId + " (call #" + count + ")";
    }

    /**
     * 测试预刷新功能
     */
    @RedisCacheable(
            cacheNames = "pre-refresh-cache",
            key = "#configKey",
            ttl = 30, // 30秒过期
            enablePreRefresh = true,
            preRefreshThreshold = 0.7 // 剩余70%时间时触发预刷新
    )
    public String getWithPreRefresh(String configKey) {
        int count = preRefreshCallCount.incrementAndGet();
        log.info("Getting config with pre-refresh for key: {}, call count: {}", configKey, count);

        return "Config value for " + configKey + " at " + System.currentTimeMillis() + " (call #" + count + ")";
    }

    /**
     * 测试随机TTL功能
     */
    @RedisCacheable(
            cacheNames = "random-ttl-cache",
            key = "#dataKey",
            ttl = 120, // 基础TTL 2分钟
            randomTtl = true,
            variance = 0.5f // 50% 的随机变化，实际TTL在60-180秒之间
    )
    public String getWithRandomTtl(String dataKey) {
        int count = randomTtlCallCount.incrementAndGet();
        log.info("Getting data with random TTL for key: {}, call count: {}", dataKey, count);

        return "Data for " + dataKey + " generated at " + System.currentTimeMillis() + " (call #" + count + ")";
    }

    /**
     * 测试组合功能：分布式锁 + 空值缓存 + 随机TTL
     */
    @RedisCacheable(
            cacheNames = "combined-features-cache",
            key = "#complexKey",
            ttl = 240,
            distributedLock = true,
            cacheNullValues = true,
            randomTtl = true,
            variance = 0.3f
    )
    public String getWithCombinedFeatures(String complexKey) {
        log.info("Getting complex data for key: {}", complexKey);

        // 模拟某些情况下返回null
        if ("null-test".equals(complexKey)) {
            return null;
        }

        // 模拟数据库查询
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return "Complex data for " + complexKey + " at " + System.currentTimeMillis();
    }

    // 重置计数器的方法，用于测试
    public void resetCallCounts() {
        distributedLockCallCount.set(0);
        internalLockCallCount.set(0);
        bloomFilterCallCount.set(0);
        preRefreshCallCount.set(0);
        randomTtlCallCount.set(0);
        log.info("All call counts reset to 0");
    }

    // Getter methods for call counts
    public int getDistributedLockCallCount() { return distributedLockCallCount.get(); }
    public int getInternalLockCallCount() { return internalLockCallCount.get(); }
    public int getBloomFilterCallCount() { return bloomFilterCallCount.get(); }
    public int getPreRefreshCallCount() { return preRefreshCallCount.get(); }
    public int getRandomTtlCallCount() { return randomTtlCallCount.get(); }
}