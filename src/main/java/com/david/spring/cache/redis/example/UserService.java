package com.david.spring.cache.redis.example;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

@Service
public class UserService {

	@RedisCacheable(value = "users", key = "#id", ttl = 100, fetchStrategy = "AUTO")
	public User getUser(Long id) {
		return User.builder().id(1L).name("David").build();
	}

	@RedisCacheEvict(value = "users", key = "#id")
	public void deleteUser(Long id) {
	}

	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class User {
		private Long id;
		private String name;
	}
}
