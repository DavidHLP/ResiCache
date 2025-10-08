package com.david.spring.cache.redis.service;

import static org.assertj.core.api.Assertions.*;

import com.david.spring.cache.redis.BasicService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest(
        classes = {SpringCacheRedis.class, RedisCacheAutoConfiguration.class, TestConfig.class})
@TestPropertySource(
        properties = {
            "spring.data.redis.host=192.168.1.111",
            "spring.data.redis.port=6379",
            "spring.data.redis.password=Alone117",
            "logging.level.com.david.spring.cache.redis=DEBUG",
        })
public class SyncCacheTest {
    @Resource private RedisTemplate<String, Object> redisTemplate;
    @Resource private SyncCacheService syncCacheService;

    @BeforeEach
    @AfterEach
    public void clearCache() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    @DisplayName("sync=true 应该防止缓存击穿")
    public void testSyncPreventsCachePenetration() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // 重置调用计数
        syncCacheService.resetCallCount();

        // 并发请求同一个不存在的缓存key
        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            startLatch.await(); // 等待所有线程就绪
                            String result = syncCacheService.getUserName(1L);
                            assertThat(result).isEqualTo("David-1");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            endLatch.countDown();
                        }
                    });
        }

        // 同时启动所有线程
        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 验证：即使10个线程并发请求，由于sync=true，实际方法只应该被调用1次
        int callCount = syncCacheService.getCallCount();
        log.info("实际方法调用次数: {}", callCount);
        assertThat(callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("sync=true 缓存命中时不会加锁")
    public void testSyncDoesNotLockOnCacheHit() throws InterruptedException {
        // 先预热缓存
        syncCacheService.resetCallCount();
        String result1 = syncCacheService.getUserName(2L);
        assertThat(result1).isEqualTo("David-2");
        assertThat(syncCacheService.getCallCount()).isEqualTo(1);

        // 并发读取已存在的缓存
        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        syncCacheService.resetCallCount();
        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            String result = syncCacheService.getUserName(2L);
                            assertThat(result).isEqualTo("David-2");
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 缓存命中，方法不应该被调用
        assertThat(syncCacheService.getCallCount()).isEqualTo(0);
    }
}

@Slf4j
@Service
class SyncCacheService extends BasicService {
    @RedisCacheable(value = "user", key = "#id", sync = true, ttl = 60)
    public String getUserName(Long id) {
        callCount.incrementAndGet();
        log.info("getUserName called with id: {}, callCount: {}", id, callCount.get());
        // 模拟耗时操作
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "David-" + id;
    }
}
