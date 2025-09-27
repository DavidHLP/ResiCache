package com.david.spring.cache.redis.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;

@Slf4j
public class RedisProCacheManager extends RedisCacheManager {
	public RedisProCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration) {
		super(cacheWriter, defaultCacheConfiguration);
	}
}