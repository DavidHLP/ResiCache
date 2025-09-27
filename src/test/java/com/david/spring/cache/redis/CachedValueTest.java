package com.david.spring.cache.redis;

import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import com.david.spring.cache.redis.core.RedisProCache;
import com.david.spring.cache.redis.service.TestService;
import com.david.spring.cache.redis.service.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
	classes = {
		SpringCacheRedis.class,
		RedisCacheAutoConfiguration.class
	},
	properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration"
	}
)
@TestPropertySource(properties = {
        "spring.data.redis.host=192.168.1.111",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=Alone117",
        "spring.redis.cache.enabled=true",
        "spring.redis.cache.default-ttl=PT1M",
        "logging.level.com.david.spring.cache.redis=DEBUG",
		"spring.jmx.enabled=false"
})
public class CachedValueTest {

    @Autowired
    private TestService testService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        testService.resetCallCounts();
        assertNotNull(redisTemplate.getConnectionFactory());
        // 清空Redis中的所有数据，确保测试干净环境
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void testCachedValueWithStats() throws InterruptedException {
        Long userId = 1L;

        // 第一次获取用户，应该从数据库加载并缓存
        User user1 = testService.getUser(userId);
        assertNotNull(user1);
        assertEquals(userId, user1.getId());
        assertEquals(1, testService.getGetUserCallCount());

        // 第二次获取用户，应该从缓存获取，并更新访问统计
        User user2 = testService.getUser(userId);
        assertNotNull(user2);
        assertEquals(userId, user2.getId());
        assertEquals(1, testService.getGetUserCallCount()); // 不应该再次调用数据库

        // 获取缓存统计信息
        RedisProCache cache = (RedisProCache) cacheManager.getCache("users");
        assertNotNull(cache);

        RedisProCache.CacheStats stats = cache.getCacheStats(userId);
        assertNotNull(stats);
        assertEquals(1, stats.visitTimes()); // 第二次访问时更新了统计
        assertTrue(stats.age() >= 0); // 存在时间应该>=0
        assertTrue(stats.remainingTtl() > 0); // 剩余时间应该>0
        assertEquals(User.class, stats.valueType());

        System.out.println("第一次缓存统计: " + stats);

        // 第三次获取用户，应该从缓存获取
        user2 = testService.getUser(userId);
        assertNotNull(user2);
        assertEquals(userId, user2.getId());
        assertEquals(1, testService.getGetUserCallCount()); // 不应该再次调用数据库

        // 再次获取统计信息，访问次数应该增加
        RedisProCache.CacheStats stats2 = cache.getCacheStats(userId);
        assertNotNull(stats2);
        assertEquals(2, stats2.visitTimes()); // 访问了两次
        assertTrue(stats2.age() >= stats.age()); // 存在时间应该增加

        System.out.println("第二次缓存统计: " + stats2);

        // 第三次获取
        User user3 = testService.getUser(userId);
        assertEquals(1, testService.getGetUserCallCount());

        RedisProCache.CacheStats stats3 = cache.getCacheStats(userId);
        assertNotNull(stats3);
        assertEquals(3, stats3.visitTimes()); // 访问了三次

        System.out.println("第三次缓存统计: " + stats3);
    }

    @Test
    void testCachedValueExpiration() throws InterruptedException {
        String key = "expiring-key";

        // 第一次调用，从数据源加载并缓存
        String value1 = testService.getValueWithRandomTtl(key);
        assertEquals("Value for expiring-key", value1);

        // 第二次调用，从缓存获取，触发访问统计更新
        String value2 = testService.getValueWithRandomTtl(key);
        assertEquals("Value for expiring-key", value2);

        // 获取缓存统计
        RedisProCache cache = (RedisProCache) cacheManager.getCache("random-ttl-cache");
        assertNotNull(cache);

        RedisProCache.CacheStats stats = cache.getCacheStats(key);
        assertNotNull(stats);
        assertEquals(1, stats.visitTimes()); // 第二次调用时触发了一次访问统计
        assertTrue(stats.remainingTtl() > 0);

        System.out.println("缓存统计信息: " + stats);
        System.out.println("剩余TTL: " + stats.remainingTtl() + " 秒");
        System.out.println("缓存年龄: " + stats.age() + " 秒");
        System.out.println("值类型: " + stats.valueType().getSimpleName());
    }

    @Test
    void testNullValueCaching() {
        String nullKey = "null";
        String regularKey = "regular";

        // 第一次调用，从数据源加载并缓存空值
        String nullValue1 = testService.getNullableValue(nullKey);
        assertNull(nullValue1);

        // 第二次调用，从缓存获取空值，触发访问统计
        String nullValue2 = testService.getNullableValue(nullKey);
        assertNull(nullValue2);

        // 第一次调用，从数据源加载并缓存普通值
        String regularValue1 = testService.getNullableValue(regularKey);
        assertEquals("Value for regular", regularValue1);

        // 第二次调用，从缓存获取普通值，触发访问统计
        String regularValue2 = testService.getNullableValue(regularKey);
        assertEquals("Value for regular", regularValue2);

        // 获取缓存统计
        RedisProCache cache = (RedisProCache) cacheManager.getCache("null-cache");
        assertNotNull(cache);

        // 空值也应该有缓存统计
        RedisProCache.CacheStats nullStats = cache.getCacheStats(nullKey);
        assertNotNull(nullStats);
        assertEquals(1, nullStats.visitTimes()); // 第二次调用时触发了访问统计
        assertEquals(Object.class, nullStats.valueType()); // 空值的类型是Object

        RedisProCache.CacheStats regularStats = cache.getCacheStats(regularKey);
        assertNotNull(regularStats);
        assertEquals(1, regularStats.visitTimes()); // 第二次调用时触发了访问统计
        assertEquals(String.class, regularStats.valueType());

        System.out.println("空值缓存统计: " + nullStats);
        System.out.println("普通值缓存统计: " + regularStats);
    }
}