package com.david.spring.cache.redis;

import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import com.david.spring.cache.redis.core.RedisCache;
import com.david.spring.cache.redis.service.AdvancedTestService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest(classes = {
        SpringCacheRedis.class,
        RedisCacheAutoConfiguration.class
})
@TestPropertySource(properties = {
        "spring.data.redis.host=192.168.1.111",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=Alone117",
        "spring.redis.cache.enabled=true",
        "spring.redis.cache.default-ttl=PT1M",
        "logging.level.com.david.spring.cache.redis=DEBUG",
})
public class AdvancedFeaturesTest {

    @Autowired
    private AdvancedTestService advancedTestService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        advancedTestService.resetCallCounts();
        assertNotNull(redisTemplate.getConnectionFactory());
        // 清空Redis中的所有数据，确保测试干净环境
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void testDistributedLock() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);
        String userId = "user-001";

        log.info("=== 测试分布式锁功能 ===");

        // 同时启动5个线程访问同一个缓存键
        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    log.info("Thread {} starting cache access", threadId);
                    String result = advancedTestService.getWithDistributedLock(userId);
                    log.info("Thread {} got result: {}", threadId, result);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 由于分布式锁的作用，实际方法应该只被调用一次
        assertEquals(1, advancedTestService.getDistributedLockCallCount());
        log.info("✓ 分布式锁测试通过：方法只被调用了 {} 次", advancedTestService.getDistributedLockCallCount());
    }

    @Test
    void testInternalLock() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);
        String productId = "product-001";

        log.info("=== 测试内部锁功能 ===");

        // 同时启动3个线程访问同一个缓存键
        for (int i = 0; i < 3; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    log.info("Thread {} starting cache access", threadId);
                    String result = advancedTestService.getWithInternalLock(productId);
                    log.info("Thread {} got result: {}", threadId, result);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 由于内部锁的作用，实际方法应该只被调用一次
        assertEquals(1, advancedTestService.getInternalLockCallCount());
        log.info("✓ 内部锁测试通过：方法只被调用了 {} 次", advancedTestService.getInternalLockCallCount());
    }

    @Test
    void testBloomFilter() {
        log.info("=== 测试布隆过滤器功能 ===");

        // 首先访问一个存在的item，应该被缓存和添加到布隆过滤器
        String existingItem = advancedTestService.getWithBloomFilter("item-001");
        assertNotNull(existingItem);
        assertEquals(1, advancedTestService.getBloomFilterCallCount());

        // 再次访问相同item，应该从缓存命中，方法不会被再次调用
        String cachedItem = advancedTestService.getWithBloomFilter("item-001");
        assertEquals(existingItem, cachedItem);
        assertEquals(1, advancedTestService.getBloomFilterCallCount());

        log.info("✓ 布隆过滤器测试通过：缓存命中正常工作");

        // 访问一个不存在的item，应该被布隆过滤器过滤掉
        // 注意：由于布隆过滤器的特性，这个测试可能需要多次运行才能看到效果
        String nonExistentItem = advancedTestService.getWithBloomFilter("definitely-not-exists");
        log.info("访问不存在的item结果: {}", nonExistentItem);
    }

    @Test
    void testPreRefresh() throws InterruptedException {
        log.info("=== 测试预刷新功能 ===");

        String configKey = "config-001";

        // 第一次访问，应该缓存数据
        String firstResult = advancedTestService.getWithPreRefresh(configKey);
        assertNotNull(firstResult);
        assertEquals(1, advancedTestService.getPreRefreshCallCount());

        // 等待一段时间，让缓存接近过期（TTL=30秒，阈值=70%，即21秒后触发预刷新）
        Thread.sleep(22000); // 等待22秒

        // 再次访问，应该触发预刷新（异步）
        String secondResult = advancedTestService.getWithPreRefresh(configKey);
        assertNotNull(secondResult);

        // 给异步预刷新一些时间执行
        Thread.sleep(2000);

        log.info("✓ 预刷新测试完成，第一次结果: {}, 第二次结果: {}", firstResult, secondResult);
        log.info("✓ 方法总调用次数: {}", advancedTestService.getPreRefreshCallCount());
    }

    @Test
    void testRandomTtl() {
        log.info("=== 测试随机TTL功能 ===");

        // 测试多个不同的key，每个都应该有不同的TTL
        for (int i = 1; i <= 3; i++) {
            String dataKey = "data-" + String.format("%03d", i);
            String result = advancedTestService.getWithRandomTtl(dataKey);
            assertNotNull(result);

            // 获取缓存统计信息查看TTL
            RedisCache cache = (RedisCache) cacheManager.getCache("random-ttl-cache");
            if (cache != null) {
                RedisCache.CacheStats stats = cache.getCacheStats(dataKey);
                if (stats != null) {
                    log.info("Key: {}, Remaining TTL: {}秒", dataKey, stats.remainingTtl());
                    // TTL应该在60-180秒之间（基础120秒 ± 50%）
                    assertTrue(stats.remainingTtl() >= 60 && stats.remainingTtl() <= 180);
                }
            }
        }

        assertEquals(3, advancedTestService.getRandomTtlCallCount());
        log.info("✓ 随机TTL测试通过：生成了3个不同TTL的缓存项");
    }

    @Test
    void testCombinedFeatures() throws InterruptedException {
        log.info("=== 测试组合功能（分布式锁 + 空值缓存 + 随机TTL）===");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);

        // 测试正常值
        String normalKey = "normal-key";
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    String result = advancedTestService.getWithCombinedFeatures(normalKey);
                    assertNotNull(result);
                    log.info("Got normal result: {}", result);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 测试空值缓存
        String nullResult = advancedTestService.getWithCombinedFeatures("null-test");
        assertNull(nullResult);

        // 再次访问null-test，应该从缓存获取null值，不再调用方法
        String secondNullResult = advancedTestService.getWithCombinedFeatures("null-test");
        assertNull(secondNullResult);

        log.info("✓ 组合功能测试通过");

        // 检查随机TTL
        RedisCache cache = (RedisCache) cacheManager.getCache("combined-features-cache");
        if (cache != null) {
            RedisCache.CacheStats stats = cache.getCacheStats(normalKey);
            if (stats != null) {
                log.info("Combined features cache TTL: {}秒", stats.remainingTtl());
                // TTL应该在168-312秒之间（基础240秒 ± 30%）
                assertTrue(stats.remainingTtl() >= 168 && stats.remainingTtl() <= 312);
            }
        }
    }
}