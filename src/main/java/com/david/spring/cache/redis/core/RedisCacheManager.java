package com.david.spring.cache.redis.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.transaction.AbstractTransactionSupportingCacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RedisCacheManager extends AbstractTransactionSupportingCacheManager {

    private final RedisTemplate<String, Object> redisTemplate;
    private final Map<String, Duration> cacheConfigurations;
    private final Duration defaultTtl;
    private final boolean allowNullValues;
    private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();

    public RedisCacheManager(RedisTemplate<String, Object> redisTemplate) {
        this(redisTemplate, Duration.ofMinutes(60), true, Collections.emptyMap());
    }

    public RedisCacheManager(RedisTemplate<String, Object> redisTemplate,
                           Duration defaultTtl,
                           boolean allowNullValues,
                           Map<String, Duration> cacheConfigurations) {
        this.redisTemplate = redisTemplate;
        this.defaultTtl = defaultTtl;
        this.allowNullValues = allowNullValues;
        this.cacheConfigurations = new ConcurrentHashMap<>(cacheConfigurations);
    }

    @Override
    protected Collection<? extends Cache> loadCaches() {
        return Collections.emptyList();
    }

    @Override
    @Nullable
    protected Cache getMissingCache(String name) {
        return cacheMap.computeIfAbsent(name, this::createRedisCache);
    }

    private RedisCache createRedisCache(String name) {
        Duration ttl = cacheConfigurations.getOrDefault(name, defaultTtl);
        log.debug("Creating Redis cache '{}' with TTL: {}", name, ttl);
        return new RedisCache(name, redisTemplate, ttl, allowNullValues);
    }

    public void setCacheConfiguration(String cacheName, Duration ttl) {
        cacheConfigurations.put(cacheName, ttl);
        cacheMap.remove(cacheName);
    }

    public Duration getCacheTtl(String cacheName) {
        return cacheConfigurations.getOrDefault(cacheName, defaultTtl);
    }

    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        log.info("RedisCacheManager initialized with default TTL: {}, allowNullValues: {}",
                defaultTtl, allowNullValues);
    }
}