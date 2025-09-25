package com.david.spring.cache.redis;

import com.david.spring.cache.redis.config.RedisCacheAutoConfiguration;
import com.david.spring.cache.redis.service.TestService;
import com.david.spring.cache.redis.service.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Objects;

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
public class RedisCacheTest {

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
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	@Test
	void testCacheableAnnotation() {
		Long userId = 1L;

		assertEquals(0, testService.getGetUserCallCount());

		User user1 = testService.getUser(userId);
		assertNotNull(user1);
		assertEquals(userId, user1.getId());
		assertEquals("User 1", user1.getName());
		assertEquals(1, testService.getGetUserCallCount());

		User user2 = testService.getUser(userId);
		assertNotNull(user2);
		assertEquals(userId, user2.getId());
		assertEquals("User 1", user2.getName());
		assertEquals(1, testService.getGetUserCallCount()); // Should be cached
	}

	@Test
	void testCacheEvictAnnotation() {
		Long userId = 1L;

		testService.getUser(userId);
		assertEquals(1, testService.getGetUserCallCount());

		testService.getUser(userId);
		assertEquals(1, testService.getGetUserCallCount()); // From cache

		User updatedUser = User.builder()
				.id(userId)
				.name("Updated User")
				.email("updated@example.com")
				.build();

		testService.updateUser(updatedUser);
		assertEquals(1, testService.getUpdateUserCallCount());

		testService.getUser(userId);
		assertEquals(2, testService.getGetUserCallCount()); // Cache was evicted
	}

	@Test
	void testCachingAnnotation() {
		Long userId = 2L;

		User user = testService.getUserWithProfile(userId);
		assertNotNull(user);

		// 使用实际的缓存键格式（key="#userId" 的结果）
		String key = String.valueOf(userId); // key="#userId" 的结果
		assertNotNull(Objects.requireNonNull(cacheManager.getCache("user-details")).get(key));
		assertNotNull(Objects.requireNonNull(cacheManager.getCache("user-profile")).get(key));

		testService.deleteUser(userId);

		assertNull(Objects.requireNonNull(cacheManager.getCache("user-details")).get(key));
		assertNull(Objects.requireNonNull(cacheManager.getCache("user-profile")).get(key));
	}

	@Test
	void testClearAllEntries() {
		testService.getUser(1L);
		testService.getUser(2L);
		testService.getUser(3L);

		// 使用实际的缓存键格式（cacheNames + ":" + key 表达式值）
		String key1 = "1"; // key="#userId" 的结果
		String key2 = "2";
		String key3 = "3";

		assertNotNull(Objects.requireNonNull(cacheManager.getCache("users")).get(key1));
		assertNotNull(Objects.requireNonNull(cacheManager.getCache("users")).get(key2));
		assertNotNull(Objects.requireNonNull(cacheManager.getCache("users")).get(key3));

		testService.clearAllUsers();

		assertNull(Objects.requireNonNull(cacheManager.getCache("users")).get(key1));
		assertNull(Objects.requireNonNull(cacheManager.getCache("users")).get(key2));
		assertNull(Objects.requireNonNull(cacheManager.getCache("users")).get(key3));
	}

	@Test
	void testCondition() {
		Long validUserId = 1L;
		Long invalidUserId = -1L;

		User validUser = testService.getUser(validUserId);
		assertNotNull(validUser);

		testService.getUser(validUserId);
		assertEquals(1, testService.getGetUserCallCount()); // Cached

		User invalidUser = testService.getUser(invalidUserId);
		assertNull(invalidUser);

		testService.getUser(invalidUserId);
		assertEquals(3, testService.getGetUserCallCount()); // Not cached due to condition
	}

	@Test
	void testSyncCache() {
		String key = "test-key";

		String value1 = testService.getSyncValue(key);
		assertEquals("Sync value for test-key", value1);

		String value2 = testService.getSyncValue(key);
		assertEquals("Sync value for test-key", value2);

		// 验证缓存是否正确存储 (key="#key" 的结果)
		assertNotNull(Objects.requireNonNull(cacheManager.getCache("sync-cache")).get(key));
	}

	@Test
	void testCacheNullValues() {
		String regularKey = "regular";
		String nullKey = "null";

		String regularValue = testService.getNullableValue(regularKey);
		assertEquals("Value for regular", regularValue);

		String nullValue = testService.getNullableValue(nullKey);
		assertNull(nullValue);

		// 验证null值也被正确缓存 (key="#key" 的结果)
		assertNotNull(Objects.requireNonNull(cacheManager.getCache("null-cache")).get(nullKey));
	}

	@Test
	void testRandomTtl() {
		String key = "random-ttl-key";

		String value = testService.getValueWithRandomTtl(key);
		assertEquals("Value for random-ttl-key", value);

		// 验证随机TTL缓存是否正确存储 (key="#key" 的结果)
		assertNotNull(Objects.requireNonNull(cacheManager.getCache("random-ttl-cache")).get(key));
	}
}