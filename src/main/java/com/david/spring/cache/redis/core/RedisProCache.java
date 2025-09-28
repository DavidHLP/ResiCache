package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.core.writer.RedisProCacheWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

@Slf4j
public class RedisProCache extends RedisCache {

	public RedisProCache(String name, RedisCacheWriter cacheWriter, RedisCacheConfiguration cacheConfiguration) {
		super(name, cacheWriter, cacheConfiguration);
	}
}