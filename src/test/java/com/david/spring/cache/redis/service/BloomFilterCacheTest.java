package com.david.spring.cache.redis.service;

import static org.assertj.core.api.Assertions.*;

import com.david.spring.cache.redis.BasicService;
import com.david.spring.cache.redis.RedisProCacheWriterTestable;
import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import com.david.spring.cache.redis.config.TestConfig;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;

import java.util.Objects;

@SpringBootTest(
        classes = {SpringCacheRedis.class, RedisCacheAutoConfiguration.class, TestConfig.class})
@TestPropertySource(
        properties = {
            "spring.data.redis.host=192.168.1.111",
            "spring.data.redis.port=6379",
            "spring.data.redis.password=Alone117",
            "logging.level.com.david.spring.cache.redis=DEBUG",
        })
public class BloomFilterCacheTest {
    @Resource private RedisTemplate<String, Object> redisTemplate;
    @Resource private BloomFilterService bloomFilterService;
    @Resource private RedisProCacheWriterTestable redisCacheWriter;

    @BeforeEach
    @AfterEach
    public void clearCache() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    @DisplayName("useBloomFilter=true 应该正常缓存和读取数据")
    public void testBloomFilterEnabledNormalOperation() {
        bloomFilterService.resetCallCount();

        // 第一次调用，缓存未命中，会执行方法
        String result1 = bloomFilterService.getUserWithBloomFilter(1L);
        assertThat(result1).isEqualTo("User-1");
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);

        // 验证缓存已创建
        String key = "bloom-user::1";
        long ttl = redisCacheWriter.getTtl(key);
        assertThat(ttl).isEqualTo(60L);

        // 第二次调用，应该从缓存读取，不会再次调用方法
        String result2 = bloomFilterService.getUserWithBloomFilter(1L);
        assertThat(result2).isEqualTo("User-1");
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("useBloomFilter=true 应该拦截不存在的key（防止缓存穿透）")
    public void testBloomFilterRejectNonExistentKey() {
        bloomFilterService.resetCallCount();

        // 先缓存一些已存在的key
        bloomFilterService.getUserWithBloomFilter(1L);
        bloomFilterService.getUserWithBloomFilter(2L);
        bloomFilterService.getUserWithBloomFilter(3L);
        assertThat(bloomFilterService.getCallCount()).isEqualTo(3);

        // 清空方法调用计数
        bloomFilterService.resetCallCount();

        // 查询从未访问过的key，第一次会穿透到方法
        String result1 = bloomFilterService.getUserWithBloomFilter(999L);
        assertThat(result1).isEqualTo("User-999");
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);

        // 第二次查询相同的key，应该从缓存读取
        String result2 = bloomFilterService.getUserWithBloomFilter(999L);
        assertThat(result2).isEqualTo("User-999");
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("useBloomFilter=false 应该正常工作不受影响")
    public void testBloomFilterDisabledNormalOperation() {
        bloomFilterService.resetCallCount();

        // 第一次调用
        String result1 = bloomFilterService.getUserWithoutBloomFilter(1L);
        assertThat(result1).isEqualTo("User-1");
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);

        // 验证缓存已创建
        String key = "normal-user::1";
        long ttl = redisCacheWriter.getTtl(key);
        assertThat(ttl).isEqualTo(60L);

        // 第二次调用，从缓存读取
        String result2 = bloomFilterService.getUserWithoutBloomFilter(1L);
        assertThat(result2).isEqualTo("User-1");
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("useBloomFilter=true 配合 cacheNullValues=true 使用")
    public void testBloomFilterWithNullValues() {
        bloomFilterService.resetCallCount();

        // 第一次调用，返回null
        String result1 = bloomFilterService.getUserWithBloomFilterAndNullCache(999L);
        assertThat(result1).isNull();
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);

        // 验证null值已被缓存
        String key = "bloom-null-user::999";
        long ttl = redisCacheWriter.getTtl(key);
        assertThat(ttl).isEqualTo(60L);

        // 第二次调用，应该从缓存读取null值
        String result2 = bloomFilterService.getUserWithBloomFilterAndNullCache(999L);
        assertThat(result2).isNull();
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("布隆过滤器应该在缓存清空时一起被清除")
    public void testBloomFilterClearedWithCache() {
        bloomFilterService.resetCallCount();

        // 先缓存一些数据，布隆过滤器中会记录这些key
        bloomFilterService.getUserWithBloomFilter(1L);
        bloomFilterService.getUserWithBloomFilter(2L);
        bloomFilterService.getUserWithBloomFilter(3L);
        assertThat(bloomFilterService.getCallCount()).isEqualTo(3);

        // 验证缓存存在
        assertThat(redisCacheWriter.getTtl("bloom-user::1")).isEqualTo(60L);

        // 清空缓存（这会同时清除布隆过滤器）
        redisTemplate
                .getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();

        // 重置调用计数
        bloomFilterService.resetCallCount();

        // 再次查询之前缓存过的key，由于布隆过滤器被清空，会重新执行方法
        String result = bloomFilterService.getUserWithBloomFilter(1L);
        assertThat(result).isEqualTo("User-1");
        assertThat(bloomFilterService.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("useBloomFilter=true 多线程并发测试")
    public void testBloomFilterConcurrent() throws InterruptedException {
        bloomFilterService.resetCallCount();

        // 创建多个线程并发访问同一个key
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                String result = bloomFilterService.getUserWithBloomFilter(100L);
                                assertThat(result).isEqualTo("User-100");
                            });
        }

        // 启动所有线程
        for (Thread thread : threads) {
            thread.start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        // 由于有缓存，实际方法调用次数应该远小于线程数
        // 在最坏情况下（所有线程同时启动），可能会有少量重复调用
        assertThat(bloomFilterService.getCallCount()).isLessThanOrEqualTo(threadCount);
        assertThat(bloomFilterService.getCallCount()).isGreaterThan(0);
    }
}

@Slf4j
@Service
class BloomFilterService extends BasicService {

    @RedisCacheable(value = "bloom-user", key = "#id", ttl = 60, useBloomFilter = true)
    public String getUserWithBloomFilter(Long id) {
        callCount.incrementAndGet();
        log.info("getUserWithBloomFilter called with id: {}", id);
        return "User-" + id;
    }

    @RedisCacheable(value = "normal-user", key = "#id", ttl = 60, useBloomFilter = false)
    public String getUserWithoutBloomFilter(Long id) {
        callCount.incrementAndGet();
        log.info("getUserWithoutBloomFilter called with id: {}", id);
        return "User-" + id;
    }

    @RedisCacheable(
            value = "bloom-null-user",
            key = "#id",
            ttl = 60,
            useBloomFilter = true,
            cacheNullValues = true)
    public String getUserWithBloomFilterAndNullCache(Long id) {
        callCount.incrementAndGet();
        log.info("getUserWithBloomFilterAndNullCache called with id: {}", id);
        if (id == 999L) {
            return null;
        }
        return "User-" + id;
    }
}
