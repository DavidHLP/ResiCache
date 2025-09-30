package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.RedisProCacheWriterTestable;
import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import com.david.spring.cache.redis.config.TestConfig;

import jakarta.annotation.Resource;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
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
public class BasicCacheTest {

    @Resource private BasicCacheTestService testService;

    @Resource private RedisProCacheWriterTestable redisCacheWriter;

    @Resource private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    @AfterEach
    public void clearCache() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    @DisplayName("测试ttl自定义功能")
    public void testCustomTtl() throws InterruptedException {
        Long userId = 1L;

        User user1 = testService.getUser(userId);
        Assertions.assertThat(user1).isNotNull();
        Assertions.assertThat(user1.getId()).isEqualTo(userId);
        Assertions.assertThat(user1.getName()).isEqualTo("David");

        String key = "user::1";
        long ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(300L);

        User user2 = testService.getUser(userId);
        Assertions.assertThat(user2).isNotNull();
        Assertions.assertThat(user2.getId()).isEqualTo(user1.getId());

        Thread.sleep(1000);
        long ttlAfter = redisCacheWriter.getExpiration(key);
        Assertions.assertThat(ttlAfter).isLessThan(ttl);
    }

    @Test
    @DisplayName("测试ttl的雪崩防护功能")
    public void testRandomTtlForAvalanchePrevention() {
        Long userId1 = 100L;
        Long userId2 = 101L;
        Long userId3 = 102L;

        testService.getUserWithRandomTtl(userId1);
        testService.getUserWithRandomTtl(userId2);
        testService.getUserWithRandomTtl(userId3);

        long ttl1 = redisCacheWriter.getTtl("user::100");
        long ttl2 = redisCacheWriter.getTtl("user::101");
        long ttl3 = redisCacheWriter.getTtl("user::102");

        Assertions.assertThat(ttl1).isBetween(150L, 600L);
        Assertions.assertThat(ttl2).isBetween(150L, 600L);
        Assertions.assertThat(ttl3).isBetween(150L, 600L);

        boolean hasRandomization = ttl1 != 300L || ttl2 != 300L || ttl3 != 300L;
        Assertions.assertThat(hasRandomization).isTrue();
    }
}
