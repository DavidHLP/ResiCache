package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.factory.CacheCreationConfig;
import com.david.spring.cache.redis.factory.CacheFactoryRegistry;
import com.david.spring.cache.redis.factory.CacheType;
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

    // 工厂模式支持
    private CacheFactoryRegistry factoryRegistry;
    private final Map<String, CacheType> cacheTypes = new ConcurrentHashMap<>();

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

    private Cache createRedisCache(String name) {
        Duration ttl = cacheConfigurations.getOrDefault(name, defaultTtl);

        // 检查是否有工厂注册表，如果有则使用工厂模式
        if (factoryRegistry != null) {
            CacheType cacheType = cacheTypes.getOrDefault(name, CacheType.REDIS);

            CacheCreationConfig config = CacheCreationConfig.builder()
                    .cacheName(name)
                    .cacheType(cacheType)
                    .redisTemplate(redisTemplate)
                    .defaultTtl(ttl)
                    .allowNullValues(allowNullValues)
                    .enableStatistics(true)
                    .build();

            log.debug("Creating cache '{}' using factory pattern with type: {}", name, cacheType);
            return factoryRegistry.createCache(config);
        }

        // 回退到直接创建Redis缓存
        log.debug("Creating Redis cache '{}' with TTL: {} (direct creation)", name, ttl);
        return new RedisCache(name, redisTemplate, ttl, allowNullValues);
    }

    public void setCacheConfiguration(String cacheName, Duration ttl) {
        cacheConfigurations.put(cacheName, ttl);
        cacheMap.remove(cacheName);
    }

    public Duration getCacheTtl(String cacheName) {
        return cacheConfigurations.getOrDefault(cacheName, defaultTtl);
    }

    /**
     * 设置工厂注册表（支持工厂模式）
     */
    public void setCacheFactoryRegistry(CacheFactoryRegistry factoryRegistry) {
        this.factoryRegistry = factoryRegistry;
        log.info("Cache factory registry configured with {} factories", factoryRegistry.getFactoryCount());
    }

    /**
     * 设置特定缓存的类型
     */
    public void setCacheType(String cacheName, CacheType cacheType) {
        cacheTypes.put(cacheName, cacheType);
        cacheMap.remove(cacheName);  // 移除已创建的缓存，强制重新创建
        log.debug("Set cache type for '{}': {}", cacheName, cacheType);
    }

    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        log.info("RedisCacheManager initialized with default TTL: {}, allowNullValues: {}",
                defaultTtl, allowNullValues);
    }
}