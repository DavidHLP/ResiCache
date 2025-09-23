package com.david.spring.cache.example;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
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
	public void test() {
		// 第一次调用：缓存中没有数据，执行方法体并存入缓存
		System.out.println("--- 第一次调用 getUser(1L) ---");
		User user1 = userService.getUser(1L);
		System.out.println("第一次获取到用户：" + user1);

		System.out.println();

		// 第二次调用：缓存中有数据，直接从缓存获取
		System.out.println("--- 第二次调用 getUser(1L) ---");
		User user2 = userService.getUser(1L);
		System.out.println("第二次获取到用户（来自缓存）：" + user2);

		System.out.println();

		// 调用 deleteUser：清除缓存中的数据
		System.out.println("--- 调用 deleteUser(1L) 清除缓存 ---");
		userService.deleteUser(1L);

		System.out.println();

		// 第三次调用：缓存已清除，再次执行方法体并存入新缓存
		System.out.println("--- 第三次调用 getUser(1L) ---");
		User user3 = userService.getUser(1L);
		System.out.println("第三次获取到用户（缓存已失效）：" + user3);
	}

	/**
	 * 用户服务类，包含缓存操作方法。
	 */
	@Service
	@Slf4j
	public static class UserService {

		/**
		 * 根据ID获取用户，并使用 Redis 缓存。
		 *
		 * @param id 用户ID
		 * @return 用户对象
		 */
		@RedisCacheable(value = "users", key = "#id", ttl = 100)
		public User getUser(Long id) {
			// 模拟从数据库获取数据
			log.info("从数据库获取用户，ID: {}", id);
			return User.builder().id(id).name("David-" + id).build();
		}

		/**
		 * 根据ID删除用户，并清除相关缓存。
		 *
		 * @param id 用户ID
		 */
		@RedisCacheEvict(value = "users", key = "#id")
		public void deleteUser(Long id) {
			log.info("执行删除用户操作，ID: {}。对应的缓存将被清除。", id);
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
