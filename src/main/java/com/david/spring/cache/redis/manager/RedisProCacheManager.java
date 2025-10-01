package com.david.spring.cache.redis.manager;

import com.david.spring.cache.redis.core.RedisProCache;
import com.david.spring.cache.redis.core.writer.RedisProCacheWriter;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

@Slf4j
public class RedisProCacheManager extends RedisCacheManager {

    private final RedisProCacheWriter redisProCacheWriter;
    private final RedisCacheConfiguration defaultConfiguration;

    public RedisProCacheManager(
            RedisProCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration) {
        super(cacheWriter, defaultCacheConfiguration);
        this.redisProCacheWriter = cacheWriter;
        this.defaultConfiguration = defaultCacheConfiguration;
    }

    @Override
    @NonNull
    protected RedisCache createRedisCache(@NonNull String name, RedisCacheConfiguration cacheConfiguration) {
        log.debug("Creating RedisProCache for cache name: {}", name);
        return new RedisProCache(name, redisProCacheWriter, resolveCacheConfiguration(cacheConfiguration));
    }

	private RedisCacheConfiguration resolveCacheConfiguration(@Nullable RedisCacheConfiguration cacheConfiguration) {
		return cacheConfiguration != null ? cacheConfiguration : getDefaultCacheConfiguration();
	}

	@Override
	public Cache getCache(@NonNull String name) {
		Cache cache = super.getCache(name);
		if (cache == null) {
			log.debug("Cache '{}' not found, creating new RedisProCache", name);
			cache = createRedisCache(name, defaultConfiguration);
			// 注册新创建的缓存
			getMissingCache(name);
		}
		return cache;
	}
}