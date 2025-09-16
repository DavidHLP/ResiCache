package com.david.spring.cache.redis.example;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.example.UserService.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = SpringCacheRedis.class)
@TestPropertySource(properties = {
		"logging.level.com.david.spring.cache.redis=DEBUG",
		"logging.level.org.springframework.cache=DEBUG"
})
public class UserServiceTest {

	@Autowired
	private UserService userService;

	@Test
	public void testRedisCachingAnnotation() {
		// 测试 RedisCaching 注解是否能正常工作
		User user = userService.get(1L);
		userService.evict(1L);
	}
}
