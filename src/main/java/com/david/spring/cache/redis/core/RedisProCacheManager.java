package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.locks.DistributedLock;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CacheAvalanche;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.impl.EvictInvocationRegistry;
import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategyManager;

import jakarta.annotation.Nonnull;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.concurrent.Executor;

@Slf4j
public class RedisProCacheManager extends RedisCacheManager {

    @Getter
    private final Map<String, RedisCacheConfiguration> initialCacheConfigurations;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisCacheWriter cacheWriter;
    private final CacheInvocationRegistry registry;
    private final EvictInvocationRegistry evictRegistry;
    private final Executor executor;
    @Getter
    private final RedisCacheConfiguration redisCacheConfiguration;
    private final DistributedLock distributedLock;
    private final CachePenetration cachePenetration;
    private final CacheBreakdown cacheBreakdown;
    private final CacheAvalanche cacheAvalanche;
    private final CacheableStrategyManager strategyManager;

    public RedisProCacheManager(
            RedisCacheWriter cacheWriter,
            Map<String, RedisCacheConfiguration> initialCacheConfigurations,
            RedisTemplate<String, Object> cacheRedisTemplate,
            RedisCacheConfiguration redisCacheConfiguration,
            CacheInvocationRegistry registry,
            EvictInvocationRegistry evictRegistry,
            Executor executor,
            DistributedLock distributedLock,
            CachePenetration cachePenetration,
            CacheBreakdown cacheBreakdown,
            CacheAvalanche cacheAvalanche,
            CacheableStrategyManager strategyManager) {
        super(cacheWriter, redisCacheConfiguration, initialCacheConfigurations);
        log.info("Initializing RedisProCacheManager with {} initial cache configurations",
                initialCacheConfigurations.size());
        this.initialCacheConfigurations = initialCacheConfigurations;
        this.redisTemplate = cacheRedisTemplate;
        this.cacheWriter = cacheWriter;
        this.redisCacheConfiguration = redisCacheConfiguration;
        this.registry = registry;
        this.evictRegistry = evictRegistry;
        this.executor = executor;
        this.distributedLock = distributedLock;
        this.cachePenetration = cachePenetration;
        this.cacheBreakdown = cacheBreakdown;
        this.cacheAvalanche = cacheAvalanche;
        this.strategyManager = strategyManager;
        log.debug("RedisProCacheManager successfully initialized with protection mechanisms and strategy manager");
    }

    @Override
    @Nonnull
    protected Collection<RedisCache> loadCaches() {
        log.info("Loading Redis caches for {} cache configurations", getInitialCacheConfigurations().size());
        List<RedisCache> caches = new LinkedList<>();
        for (Map.Entry<String, RedisCacheConfiguration> entry : getInitialCacheConfigurations().entrySet()) {
            String cacheName = entry.getKey();
            log.debug("Loading cache: {}", cacheName);
            caches.add(createRedisCache(cacheName, entry.getValue()));
        }
        log.info("Successfully loaded {} Redis caches", caches.size());
        return caches;
    }

    @Override
    @Nonnull
    public RedisCache createRedisCache(
            @NonNull String name, @Nullable RedisCacheConfiguration cacheConfig) {
        log.debug("Creating Redis cache: {}", name);
        RedisCacheConfiguration config = getInitialCacheConfigurations().getOrDefault(name, redisCacheConfiguration);

        RedisProCache cache = new RedisProCache(
                name,
                cacheWriter,
                config,
                redisTemplate,
                registry,
                evictRegistry,
                executor,
                distributedLock,
                cachePenetration,
                cacheBreakdown,
                cacheAvalanche,
                strategyManager);
        log.debug("Successfully created Redis cache: {} with TTL: {}", name,
                config.getTtl() != null ? config.getTtl().getSeconds() + "s" : "default");
        return cache;
    }

    public void initializeCaches() {
        log.info("Initializing Redis caches");
        super.initializeCaches();
        log.debug("Redis caches initialization completed");
    }
}
