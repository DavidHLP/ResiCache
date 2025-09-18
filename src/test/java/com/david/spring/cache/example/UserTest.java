package com.david.spring.cache.example;

import com.david.spring.cache.redis.SpringCacheRedis;
import com.david.spring.cache.redis.example.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = SpringCacheRedis.class)
public class UserTest {
	@Autowired
	private UserService userService;

	@Test
	public void test() {
		UserService.User user = userService.getUser(1L);
	}
}
