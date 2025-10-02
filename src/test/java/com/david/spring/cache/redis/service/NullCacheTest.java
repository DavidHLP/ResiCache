package com.david.spring.cache.redis.service;

import static org.assertj.core.api.Assertions.*;

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
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(
        classes = {SpringCacheRedis.class, RedisCacheAutoConfiguration.class, TestConfig.class})
@TestPropertySource(
        properties = {
            "spring.data.redis.host=192.168.1.111",
            "spring.data.redis.port=6379",
            "spring.data.redis.password=Alone117",
            "logging.level.com.david.spring.cache.redis=DEBUG",
        })
public class NullCacheTest {
    @Resource private RedisTemplate<String, Object> redisTemplate;
    @Resource private NullCacheService nullCacheService;
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
    @DisplayName("cacheNullValues=true 应该缓存null值")
    public void testCacheNullValuesTrue() {
        // 重置调用计数
        nullCacheService.resetCallCount();

        // 第一次调用，返回null
        String result1 = nullCacheService.getUserNameWithNullCache(999L);
        assertThat(result1).isNull();
        assertThat(nullCacheService.getCallCount()).isEqualTo(1);

        // 验证缓存已创建
        String key = "user::999";
        long ttl = redisCacheWriter.getTtl(key);
        assertThat(ttl).isEqualTo(60L);

        // 第二次调用，应该从缓存获取null值，不会再次调用方法
        String result2 = nullCacheService.getUserNameWithNullCache(999L);
        assertThat(result2).isNull();
        assertThat(nullCacheService.getCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("cacheNullValues=false 不应该缓存null值")
    public void testCacheNullValuesFalse() {
        // 重置调用计数
        nullCacheService.resetCallCount();

        // 第一次调用，返回null
        String result1 = nullCacheService.getUserNameWithoutNullCache(999L);
        assertThat(result1).isNull();
        assertThat(nullCacheService.getCallCount()).isEqualTo(1);

        // 验证缓存未创建
        String key = "user::999";
        long ttl = redisCacheWriter.getTtl(key);
        assertThat(ttl).isEqualTo(-1L);

        // 第二次调用，由于null值未被缓存，会再次调用方法
        String result2 = nullCacheService.getUserNameWithoutNullCache(999L);
        assertThat(result2).isNull();
        assertThat(nullCacheService.getCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("cacheNullValues=true 应该缓存null值和正常值")
    public void testCacheNullAndNormalValues() {
        nullCacheService.resetCallCount();

        // 测试正常值
        String normalResult = nullCacheService.getUserNameWithNullCache(1L);
        assertThat(normalResult).isEqualTo("User-1");
        assertThat(nullCacheService.getCallCount()).isEqualTo(1);

        // 再次获取正常值，应该从缓存读取
        String cachedNormalResult = nullCacheService.getUserNameWithNullCache(1L);
        assertThat(cachedNormalResult).isEqualTo("User-1");
        assertThat(nullCacheService.getCallCount()).isEqualTo(1);

        // 测试null值
        String nullResult = nullCacheService.getUserNameWithNullCache(999L);
        assertThat(nullResult).isNull();
        assertThat(nullCacheService.getCallCount()).isEqualTo(2);

        // 再次获取null值，应该从缓存读取
        String cachedNullResult = nullCacheService.getUserNameWithNullCache(999L);
        assertThat(cachedNullResult).isNull();
        assertThat(nullCacheService.getCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("cacheNullValues=true 配合unless条件使用")
    public void testCacheNullValuesWithUnless() {
        nullCacheService.resetCallCount();

        // id=1000，返回null，但unless条件会阻止缓存
        String result1 = nullCacheService.getUserNameWithNullCacheAndUnless(1000L);
        assertThat(result1).isNull();
        assertThat(nullCacheService.getCallCount()).isEqualTo(1);

        // 验证缓存未创建
        String key = "user::1000";
        long ttl = redisCacheWriter.getTtl(key);
        assertThat(ttl).isEqualTo(-1L);

        // 第二次调用，会再次执行方法
        String result2 = nullCacheService.getUserNameWithNullCacheAndUnless(1000L);
        assertThat(result2).isNull();
        assertThat(nullCacheService.getCallCount()).isEqualTo(2);

        // id=1，返回正常值，应该被缓存
        nullCacheService.resetCallCount();
        String normalResult = nullCacheService.getUserNameWithNullCacheAndUnless(1L);
        assertThat(normalResult).isEqualTo("User-1");
        assertThat(nullCacheService.getCallCount()).isEqualTo(1);

        // 再次调用，应该从缓存读取
        String cachedResult = nullCacheService.getUserNameWithNullCacheAndUnless(1L);
        assertThat(cachedResult).isEqualTo("User-1");
        assertThat(nullCacheService.getCallCount()).isEqualTo(1);
    }
}

@Slf4j
@Service
class NullCacheService {
    private final AtomicInteger callCount = new AtomicInteger(0);

    @RedisCacheable(value = "user", key = "#id", ttl = 60, cacheNullValues = true)
    public String getUserNameWithNullCache(Long id) {
        callCount.incrementAndGet();
        log.info("getUserNameWithNullCache called with id: {}", id);
        if (id == 999L) {
            return null;
        }
        return "User-" + id;
    }

    @RedisCacheable(value = "user", key = "#id", ttl = 60, cacheNullValues = false)
    public String getUserNameWithoutNullCache(Long id) {
        callCount.incrementAndGet();
        log.info("getUserNameWithoutNullCache called with id: {}", id);
        if (id == 999L) {
            return null;
        }
        return "User-" + id;
    }

    @RedisCacheable(
            value = "user",
            key = "#id",
            ttl = 60,
            cacheNullValues = true,
            unless = "#result == null")
    public String getUserNameWithNullCacheAndUnless(Long id) {
        callCount.incrementAndGet();
        log.info("getUserNameWithNullCacheAndUnless called with id: {}", id);
        if (id == 1000L) {
            return null;
        }
        return "User-" + id;
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void resetCallCount() {
        callCount.set(0);
    }
}
