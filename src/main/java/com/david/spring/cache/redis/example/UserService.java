package com.david.spring.cache.redis.example;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

@Service
public class UserService {

	@RedisCacheable(value = "users", key = "#id", ttl = 1000)
	public User getUser(Long id) {
		return User.builder().id(1L).name("David").build();
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
