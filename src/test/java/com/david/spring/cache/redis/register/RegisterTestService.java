package com.david.spring.cache.redis.register;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RegisterTestService {
	@RedisCaching(
			redisCacheable = {
					@RedisCacheable(cacheNames = "user-details", key = "#userId", ttl = 600),
					@RedisCacheable(cacheNames = "user-profile", key = "#userId", ttl = 300)
			}
	)
	public String getUserWithProfile(Long userId) {
		log.info("Getting user with profile {} from database", userId);
		return "User " + userId;
	}
}
