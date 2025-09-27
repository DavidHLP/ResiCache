package com.david.spring.cache.redis.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

@Slf4j
public class RedisProCache extends RedisCache {
	protected RedisProCache(String name, RedisCacheWriter cacheWriter, RedisCacheConfiguration cacheConfiguration) {
		super(name, cacheWriter, cacheConfiguration);
	}
}