package com.david.spring.cache.redis.service;

import static org.assertj.core.api.Assertions.*;

import com.david.spring.cache.redis.BasicService;
import com.david.spring.cache.redis.RedisProCacheWriterTestable;
import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import com.david.spring.cache.redis.config.TestConfig;
import com.david.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;
import com.david.spring.cache.redis.core.writer.support.refresh.PreRefreshSupport;

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
public class PreRefreshCacheTest {
    @Resource private RedisTemplate<String, Object> redisTemplate;
    @Resource private PreRefreshCacheService preRefreshCacheService;
    @Resource private RedisProCacheWriterTestable redisCacheWriter;
    @Resource private PreRefreshSupport preRefreshSupport;

    @BeforeEach
    @AfterEach
    public void clearCache() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    @DisplayName("同步预刷新模式：缓存接近过期时应触发立即重新加载")
    public void testSyncPreRefreshMode() throws InterruptedException {
        preRefreshCacheService.resetCallCount();

        // 第一次调用，缓存数据，TTL=10秒，阈值=0.5（剩余50%时触发）
        String result1 = preRefreshCacheService.getDataWithSyncPreRefresh(1L);
        assertThat(result1).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 验证缓存已创建
        String key = "sync-pre-refresh::1";
        long ttl1 = redisCacheWriter.getTtl(key);
        assertThat(ttl1).isEqualTo(10L);

        // 第二次调用（在预刷新阈值之前），应从缓存获取
        String result2 = preRefreshCacheService.getDataWithSyncPreRefresh(1L);
        assertThat(result2).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 等待6秒，超过50%阈值（10秒 * 50% = 5秒）
        Thread.sleep(6000);

        // 第三次调用，触发同步预刷新
        // Writer返回null -> Spring Cache检测到未命中 -> 立即调用方法重新加载 -> 返回新数据
        String result3 = preRefreshCacheService.getDataWithSyncPreRefresh(1L);
        assertThat(result3).isEqualTo("Data-1"); // 返回重新加载的数据
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(2); // 方法被立即调用

        // 第四次调用，应从新缓存获取
        String result4 = preRefreshCacheService.getDataWithSyncPreRefresh(1L);
        assertThat(result4).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(2); // 方法未被再次调用
    }

    @Test
    @DisplayName("异步预刷新模式：缓存接近过期时应返回旧值并后台刷新")
    public void testAsyncPreRefreshMode() throws InterruptedException {
        preRefreshCacheService.resetCallCount();

        // 第一次调用，缓存数据
        String result1 = preRefreshCacheService.getDataWithAsyncPreRefresh(1L);
        assertThat(result1).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 验证缓存已创建
        String key = "async-pre-refresh::1";
        long ttl1 = redisCacheWriter.getTtl(key);
        assertThat(ttl1).isEqualTo(10L);

        // 等待6秒，超过50%阈值
        Thread.sleep(6000);

        // 第二次调用，应触发异步预刷新，返回旧值
        String result2 = preRefreshCacheService.getDataWithAsyncPreRefresh(1L);
        assertThat(result2).isEqualTo("Data-1"); // 异步模式返回旧值
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1); // 方法未立即调用

        // 等待异步任务完成（缓存被删除）
        Thread.sleep(1000);

        // 第三次调用，缓存已被删除，会重新加载
        String result3 = preRefreshCacheService.getDataWithAsyncPreRefresh(1L);
        assertThat(result3).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("未启用预刷新：缓存应正常工作直到过期")
    public void testNoPreRefresh() throws InterruptedException {
        preRefreshCacheService.resetCallCount();

        // 第一次调用
        String result1 = preRefreshCacheService.getDataWithoutPreRefresh(1L);
        assertThat(result1).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 等待6秒
        Thread.sleep(6000);

        // 第二次调用，仍然从缓存获取（未启用预刷新）
        String result2 = preRefreshCacheService.getDataWithoutPreRefresh(1L);
        assertThat(result2).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 等待到过期（总共超过10秒）
        Thread.sleep(5000);

        // 第三次调用，缓存已过期，重新加载
        String result3 = preRefreshCacheService.getDataWithoutPreRefresh(1L);
        assertThat(result3).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("不同预刷新阈值测试：30%阈值")
    public void testPreRefreshThreshold30() throws InterruptedException {
        preRefreshCacheService.resetCallCount();

        // TTL=10秒，阈值=0.3（剩余30%时触发，即7秒后触发）
        String result1 = preRefreshCacheService.getDataWithThreshold30(1L);
        assertThat(result1).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 等待5秒，未超过阈值
        Thread.sleep(5000);
        String result2 = preRefreshCacheService.getDataWithThreshold30(1L);
        assertThat(result2).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 等待3秒（总共8秒），超过阈值
        Thread.sleep(3000);
        String result3 = preRefreshCacheService.getDataWithThreshold30(1L);
        assertThat(result3).isEqualTo("Data-1"); // 触发预刷新，立即重新加载
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(2); // 方法被调用
    }

    @Test
    @DisplayName("预刷新与随机TTL组合测试")
    public void testPreRefreshWithRandomTtl() throws InterruptedException {
        preRefreshCacheService.resetCallCount();

        // 第一次调用
        String result1 = preRefreshCacheService.getDataWithPreRefreshAndRandomTtl(1L);
        assertThat(result1).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 验证缓存已创建，TTL应在合理范围内（基础10秒±波动）
        String key = "pre-refresh-random::1";
        long ttl = redisCacheWriter.getTtl(key);
        assertThat(ttl).isBetween(5L, 20L); // 允许一定的随机波动

        // 第二次调用，应从缓存获取
        String result2 = preRefreshCacheService.getDataWithPreRefreshAndRandomTtl(1L);
        assertThat(result2).isEqualTo("Data-1");
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("异步预刷新防止重复刷新测试")
    public void testAsyncPreRefreshNoDuplicateRefresh() throws InterruptedException {
        preRefreshCacheService.resetCallCount();

        // 第一次调用
        String result1 = preRefreshCacheService.getDataWithAsyncPreRefresh(2L);
        assertThat(result1).isEqualTo("Data-2");

        // 等待超过阈值
        Thread.sleep(6000);

        // 并发访问，应只触发一次异步刷新
        String key = "async-pre-refresh::2";
        for (int i = 0; i < 5; i++) {
            preRefreshCacheService.getDataWithAsyncPreRefresh(2L);
        }

        // 检查是否有正在刷新的任务
        int refreshingCount = preRefreshSupport.getRefreshingKeyCount();
        assertThat(refreshingCount).isLessThanOrEqualTo(1); // 最多只有一个刷新任务
    }

    @Test
    @DisplayName("预刷新与null值缓存组合测试")
    public void testPreRefreshWithNullCache() throws InterruptedException {
        preRefreshCacheService.resetCallCount();

        // 调用返回null的方法
        String result1 = preRefreshCacheService.getDataWithPreRefreshAndNullCache(999L);
        assertThat(result1).isNull();
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 验证null值已被缓存
        String key = "pre-refresh-null::999";
        long ttl = redisCacheWriter.getTtl(key);
        assertThat(ttl).isEqualTo(10L);

        // 第二次调用，应从缓存获取null
        String result2 = preRefreshCacheService.getDataWithPreRefreshAndNullCache(999L);
        assertThat(result2).isNull();
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(1);

        // 等待超过阈值
        Thread.sleep(6000);

        // 第三次调用，触发预刷新，重新加载null值
        String result3 = preRefreshCacheService.getDataWithPreRefreshAndNullCache(999L);
        assertThat(result3).isNull(); // 重新加载的null值
        assertThat(preRefreshCacheService.getCallCount()).isEqualTo(2); // 方法被调用
    }

    @Test
    @DisplayName("线程池状态监控测试")
    public void testThreadPoolStats() throws InterruptedException {
        preRefreshCacheService.resetCallCount();

        // 触发几次异步预刷新
        for (int i = 0; i < 3; i++) {
            preRefreshCacheService.getDataWithAsyncPreRefresh((long) i);
        }

        Thread.sleep(6000);

        for (int i = 0; i < 3; i++) {
            preRefreshCacheService.getDataWithAsyncPreRefresh((long) i);
        }

        // 获取线程池统计信息
        String stats = preRefreshSupport.getThreadPoolStats();
        assertThat(stats).contains("PreRefreshThreadPool");
        assertThat(stats).contains("active=");
        assertThat(stats).contains("poolSize=");
    }
}

@Slf4j
@Service
class PreRefreshCacheService extends BasicService {

    /** 同步预刷新模式测试 TTL=10秒，阈值=0.5（剩余50%时触发） */
    @RedisCacheable(
            value = "sync-pre-refresh",
            key = "#id",
            ttl = 10,
            enablePreRefresh = true,
            preRefreshThreshold = 0.5,
            preRefreshMode = PreRefreshMode.SYNC)
    public String getDataWithSyncPreRefresh(Long id) {
        callCount.incrementAndGet();
        log.info("getDataWithSyncPreRefresh called with id: {}", id);
        return "Data-" + id;
    }

    /** 异步预刷新模式测试 TTL=10秒，阈值=0.5（剩余50%时触发） */
    @RedisCacheable(
            value = "async-pre-refresh",
            key = "#id",
            ttl = 10,
            enablePreRefresh = true,
            preRefreshThreshold = 0.5,
            preRefreshMode = PreRefreshMode.ASYNC)
    public String getDataWithAsyncPreRefresh(Long id) {
        callCount.incrementAndGet();
        log.info("getDataWithAsyncPreRefresh called with id: {}", id);
        return "Data-" + id;
    }

    /** 未启用预刷新 */
    @RedisCacheable(value = "no-pre-refresh", key = "#id", ttl = 10, enablePreRefresh = false)
    public String getDataWithoutPreRefresh(Long id) {
        callCount.incrementAndGet();
        log.info("getDataWithoutPreRefresh called with id: {}", id);
        return "Data-" + id;
    }

    /** 预刷新阈值30%测试 TTL=10秒，阈值=0.3（剩余30%时触发，即7秒后） */
    @RedisCacheable(
            value = "pre-refresh-30",
            key = "#id",
            ttl = 10,
            enablePreRefresh = true,
            preRefreshThreshold = 0.3,
            preRefreshMode = PreRefreshMode.SYNC)
    public String getDataWithThreshold30(Long id) {
        callCount.incrementAndGet();
        log.info("getDataWithThreshold30 called with id: {}", id);
        return "Data-" + id;
    }

    /** 预刷新与随机TTL组合 */
    @RedisCacheable(
            value = "pre-refresh-random",
            key = "#id",
            ttl = 10,
            randomTtl = true,
            variance = 0.5F,
            enablePreRefresh = true,
            preRefreshThreshold = 0.3,
            preRefreshMode = PreRefreshMode.SYNC)
    public String getDataWithPreRefreshAndRandomTtl(Long id) {
        callCount.incrementAndGet();
        log.info("getDataWithPreRefreshAndRandomTtl called with id: {}", id);
        return "Data-" + id;
    }

    /** 预刷新与null值缓存组合 */
    @RedisCacheable(
            value = "pre-refresh-null",
            key = "#id",
            ttl = 10,
            cacheNullValues = true,
            enablePreRefresh = true,
            preRefreshThreshold = 0.5,
            preRefreshMode = PreRefreshMode.SYNC)
    public String getDataWithPreRefreshAndNullCache(Long id) {
        callCount.incrementAndGet();
        log.info("getDataWithPreRefreshAndNullCache called with id: {}", id);
        if (id == 999L) {
            return null;
        }
        return "Data-" + id;
    }
}
