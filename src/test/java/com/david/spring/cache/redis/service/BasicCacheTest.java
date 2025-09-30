package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.RedisProCacheWriterTestable;
import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import com.david.spring.cache.redis.config.TestConfig;

import jakarta.annotation.Resource;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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

    @Test
    @DisplayName("测试ttl自定义功能")
    public void testCustomTtl() throws InterruptedException {
        Long userId = 1L;

        // 第一次调用，应该触发方法执行并缓存结果
        User user1 = testService.getUser(userId);
        Assertions.assertThat(user1).isNotNull();
        Assertions.assertThat(user1.getId()).isEqualTo(userId);
        Assertions.assertThat(user1.getName()).isEqualTo("David");

        // 验证TTL设置为300秒
        String key = "user::1";
        long ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(300L);

        // 第二次调用，应该从缓存中获取，不会触发方法执行
        User user2 = testService.getUser(userId);
        Assertions.assertThat(user2).isNotNull();
        Assertions.assertThat(user2.getId()).isEqualTo(user1.getId());

        // 等待1秒后验证TTL减少
        Thread.sleep(1000);
        long ttlAfter = redisCacheWriter.getExpiration(key);
        Assertions.assertThat(ttlAfter).isLessThan(ttl);
    }

    @Test
    @DisplayName("测试ttl的雪崩防护功能")
    public void testRandomTtlForAvalanchePrevention() {
        // 多次调用带随机TTL的方法，验证TTL的随机性
        Long userId1 = 100L;
        Long userId2 = 101L;
        Long userId3 = 102L;

        testService.getUserWithRandomTtl(userId1);
        testService.getUserWithRandomTtl(userId2);
        testService.getUserWithRandomTtl(userId3);

        // 获取三个不同key的TTL
        long ttl1 = redisCacheWriter.getTtl("user::100");
        long ttl2 = redisCacheWriter.getTtl("user::101");
        long ttl3 = redisCacheWriter.getTtl("user::102");

        // 验证TTL都在合理范围内 (基础TTL=300, variance=0.5, 所以范围应该在 [150, 600] 之间)
        Assertions.assertThat(ttl1).isBetween(150L, 600L);
        Assertions.assertThat(ttl2).isBetween(150L, 600L);
        Assertions.assertThat(ttl3).isBetween(150L, 600L);

        // 验证至少有一个TTL与基础TTL不同（证明随机化生效）
        boolean hasRandomization = ttl1 != 300L || ttl2 != 300L || ttl3 != 300L;
        Assertions.assertThat(hasRandomization).isTrue();
    }
}
