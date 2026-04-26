package io.github.davidhlp.spring.cache.redis.manager;

import io.github.davidhlp.spring.cache.redis.core.RedisProCache;
import io.github.davidhlp.spring.cache.redis.core.writer.RedisProCacheWriter;

import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meterRegistry;

    public RedisProCacheManager(
            RedisProCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            MeterRegistry meterRegistry) {
        super(cacheWriter, defaultCacheConfiguration);
        this.redisProCacheWriter = cacheWriter;
        this.defaultConfiguration = defaultCacheConfiguration;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @NonNull
    protected RedisCache createRedisCache(
            @NonNull String name, RedisCacheConfiguration cacheConfiguration) {
        log.debug("Creating RedisProCache for cache name: {}", name);
        return new RedisProCache(
                name,
                redisProCacheWriter,
                resolveCacheConfiguration(cacheConfiguration),
                meterRegistry);
    }

    private RedisCacheConfiguration resolveCacheConfiguration(
            @Nullable RedisCacheConfiguration cacheConfiguration) {
        return cacheConfiguration != null ? cacheConfiguration : getDefaultCacheConfiguration();
    }

    @Override
    public Cache getCache(@NonNull String name) {
        // 先尝试从父类获取缓存
        Cache cache = super.getCache(name);
        if (cache != null) {
            return cache;
        }

        // 父类没有缓存，创建新的 RedisProCache
        log.debug("Cache '{}' not found, creating new RedisProCache", name);
        return createRedisCache(name, defaultConfiguration);
    }
}
