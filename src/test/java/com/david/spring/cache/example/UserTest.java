package com.david.spring.cache.example;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Service;

/**
 * Spring Boot 测试类，演示 Redis 缓存使用。
 * 包含嵌套的实体类和服务类。
 */
@SpringBootTest(classes = {SpringCacheRedis.class, UserTest.UserService.class}, properties = {"logging.level.com.david.spring.cache.redis=DEBUG", "logging.level.org.springframework.data.redis=DEBUG"})
public class UserTest {

	@Resource
	private UserService userService;

	@Test
	public void testRedisCaching() {
		System.out.println("=== 测试 RedisCaching 组合注解功能 ===");

		// 第一次调用：缓存中没有数据，执行方法体并存入多个缓存
		System.out.println("--- 第一次调用 getUserWithMultiCache(2L) ---");
		User user1 = userService.getUserWithMultiCache(2L);
		System.out.println("第一次获取到用户：" + user1);
	}

	/**
	 * 用户服务类，包含缓存操作方法。
	 */
	@Service
	@Slf4j
	public static class UserService {

		/**
		 * 根据ID删除用户，并清除相关缓存。
		 *
		 * @param id 用户ID
		 */
		@RedisCacheEvict(value = "users", key = "#id")
		public void deleteUser(Long id) {
			log.info("执行删除用户操作，ID: {}。对应的缓存将被清除。", id);
		}

		/**
		 * 使用@RedisCaching同时缓存到多个缓存区域
		 *
		 * @param id 用户ID
		 * @return 用户对象
		 */
		@RedisCaching(
				cacheable = {
						@RedisCacheable(value = "users", key = "#id", ttl = 100),
						@RedisCacheable(value = "user-cache", key = "#id", ttl = 200)
				}
		)
		public User getUserWithMultiCache(Long id) {
			log.info("从数据库获取用户（多缓存），ID: {}", id);
			return User.builder().id(id).name("David-Multi-" + id).build();
		}
	}

	/**
	 * 用户实体类。
	 */
	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class User {
		private Long id;
		private String name;
	}
}
