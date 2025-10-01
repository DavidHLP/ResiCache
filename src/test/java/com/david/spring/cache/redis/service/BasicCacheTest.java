package com.david.spring.cache.redis.service;

import com.david.spring.cache.redis.RedisProCacheWriterTestable;
import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import com.david.spring.cache.redis.config.TestConfig;

import jakarta.annotation.Resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;

import java.io.Serializable;
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
        long[] tels = new long[20];

        for (int i = 0; i < 20; i++) {
            testService.getUserWithRandomTtl((long) i);
            tels[i] = redisCacheWriter.getTtl("user::" + i);
        }

        // 验证每个TTL都在合理范围内（基于variance=0.5，理论范围是 [150, 600]）
        for (long ttl : tels) {
            Assertions.assertThat(ttl).isBetween(150L, 600L);
        }

        // 验证至少有一个TTL不等于基础值300L（随机化生效）
        boolean hasRandomization = false;
        for (long ttl : tels) {
            if (ttl != 300L) {
                hasRandomization = true;
                break;
            }
        }
        Assertions.assertThat(hasRandomization)
                .as("Expected at least one TTL to be randomized from base value 300")
                .isTrue();

        // 验证TTL值有差异（不是所有值都相同）
        long firstTtl = tels[0];
        boolean hasDiversity = false;
        for (int i = 1; i < tels.length; i++) {
            if (tels[i] != firstTtl) {
                hasDiversity = true;
                break;
            }
        }
        Assertions.assertThat(hasDiversity).as("Expected TTL values to have diversity").isTrue();
    }

    @Test
    @DisplayName("测试condition条件：当条件满足时才缓存")
    public void testConditionTrue() {
        Long userId = 20L;

        // 第一次调用，id > 10，满足条件，应该缓存
        User user1 = testService.getUserWithCondition(userId);
        Assertions.assertThat(user1).isNotNull();
        Assertions.assertThat(user1.getName()).isEqualTo("Alice");

        // 验证缓存已创建
        String key = "user::20";
        long ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(300L);

        // 第二次调用，应该从缓存获取
        User user2 = testService.getUserWithCondition(userId);
        Assertions.assertThat(user2).isNotNull();
        Assertions.assertThat(user2.getId()).isEqualTo(user1.getId());
    }

    @Test
    @DisplayName("测试condition条件：当条件不满足时不缓存")
    public void testConditionFalse() {
        Long userId = 5L;

        // 第一次调用，id <= 10，不满足条件，不应该缓存
        User user1 = testService.getUserWithCondition(userId);
        Assertions.assertThat(user1).isNotNull();
        Assertions.assertThat(user1.getName()).isEqualTo("Alice");

        // 验证缓存未创建
        String key = "user::5";
        long ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(-1L);

        // 第二次调用，由于没有缓存，会再次执行方法
        User user2 = testService.getUserWithCondition(userId);
        Assertions.assertThat(user2).isNotNull();

        // 验证仍然没有缓存
        ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(-1L);
    }

    @Test
    @DisplayName("测试unless条件：正常结果应该被缓存")
    public void testUnlessCacheNormalResult() {
        Long userId = 200L;

        // 第一次调用，返回正常用户，不满足unless条件（name != 'Anonymous'），应该缓存
        User user1 = testService.getUserWithUnless(userId);
        Assertions.assertThat(user1).isNotNull();
        Assertions.assertThat(user1.getName()).isEqualTo("Bob");

        // 验证缓存已创建
        String key = "user::200";
        long ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(300L);

        // 第二次调用，应该从缓存获取
        User user2 = testService.getUserWithUnless(userId);
        Assertions.assertThat(user2).isNotNull();
        Assertions.assertThat(user2.getName()).isEqualTo("Bob");
    }

    @Test
    @DisplayName("测试unless条件：匹配unless条件的结果不应该被缓存")
    public void testUnlessSkipCacheForAnonymous() {
        Long userId = 999L;

        // 第一次调用，返回Anonymous用户，满足unless条件，不应该缓存
        User user1 = testService.getUserWithUnless(userId);
        Assertions.assertThat(user1).isNotNull();
        Assertions.assertThat(user1.getName()).isEqualTo("Anonymous");

        // 验证缓存未创建
        String key = "user::999";
        long ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(-1L);

        // 第二次调用，由于没有缓存，会再次执行方法
        User user2 = testService.getUserWithUnless(userId);
        Assertions.assertThat(user2).isNotNull();
        Assertions.assertThat(user2.getName()).isEqualTo("Anonymous");

        // 验证仍然没有缓存
        ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(-1L);
    }

    @Test
    @DisplayName("测试condition和unless组合：条件满足且结果不为null时缓存")
    public void testConditionAndUnlessBothPass() {
        Long userId = 300L;

        // 第一次调用，id > 0 且 result != null，应该缓存
        User user1 = testService.getUserWithConditionAndUnless(userId);
        Assertions.assertThat(user1).isNotNull();
        Assertions.assertThat(user1.getName()).isEqualTo("Charlie");

        // 验证缓存已创建
        String key = "user::300";
        long ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(300L);

        // 第二次调用，应该从缓存获取
        User user2 = testService.getUserWithConditionAndUnless(userId);
        Assertions.assertThat(user2).isNotNull();
        Assertions.assertThat(user2.getId()).isEqualTo(user1.getId());
    }

    @Test
    @DisplayName("测试condition和unless组合：条件不满足时不缓存")
    public void testConditionAndUnlessConditionFails() {
        Long userId = 0L;

        // 第一次调用，id = 0 不满足condition，不应该缓存
        User user1 = testService.getUserWithConditionAndUnless(userId);
        Assertions.assertThat(user1).isNull();

        // 验证缓存未创建
        String key = "user::0";
        long ttl = redisCacheWriter.getTtl(key);
        Assertions.assertThat(ttl).isEqualTo(-1L);
    }
}

@Slf4j
@Service
class BasicCacheTestService {

    @RedisCacheable(value = "user", key = "#id", ttl = 300)
    public User getUser(Long id) {
        return User.builder().id(id).name("David").email("<EMAIL>").build();
    }

    @RedisCacheable(value = "user", key = "#id", ttl = 300, randomTtl = true, variance = 0.5F)
    public User getUserWithRandomTtl(Long id) {
        return User.builder().id(id).name("David").email("<EMAIL>").build();
    }

    @RedisCacheable(value = "user", key = "#id", ttl = 300, condition = "#id > 10")
    public User getUserWithCondition(Long id) {
        log.info("getUserWithCondition called with id: {}", id);
        return User.builder().id(id).name("Alice").email("<EMAIL>").build();
    }

    @RedisCacheable(value = "user", key = "#id", ttl = 300, unless = "#result.name == 'Anonymous'")
    public User getUserWithUnless(Long id) {
        log.info("getUserWithUnless called with id: {}", id);
        if (id == 999L) {
            return User.builder().id(id).name("Anonymous").email("").build();
        }
        return User.builder().id(id).name("Bob").email("<EMAIL>").build();
    }

    @RedisCacheable(
            value = "user",
            key = "#id",
            ttl = 300,
            condition = "#id > 0",
            unless = "#result == null")
    public User getUserWithConditionAndUnless(Long id) {
        log.info("getUserWithConditionAndUnless called with id: {}", id);
        if (id == 0L) {
            return null;
        }
        return User.builder().id(id).name("Charlie").email("<EMAIL>").build();
    }
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class User implements Serializable {
    private Long id;
    private String name;
    private String email;
}
