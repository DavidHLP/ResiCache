package com.david.spring.cache.redis.config;

import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 自定义的RedisCacheManager，支持动态设置TTL */
public class CustomRedisCacheManager extends RedisCacheManager {

    private final RedisCacheConfiguration defaultCacheConfiguration;
    private final Map<String, RedisCacheConfiguration> initialCacheConfigurations =
            new ConcurrentHashMap<>();

    public CustomRedisCacheManager(
            RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration) {
        super(cacheWriter, defaultCacheConfiguration);
        this.defaultCacheConfiguration = defaultCacheConfiguration;
    }

    /**
     * 允许动态配置缓存，支持不同的TTL
     *
     * @param cacheName 缓存名称
     * @param ttl 缓存过期时间（秒）
     */
    public void configureTtlForCache(String cacheName, long ttl) {
        if (ttl > 0) {
            RedisCacheConfiguration configuration =
                    defaultCacheConfiguration.entryTtl(Duration.ofSeconds(ttl));
            initialCacheConfigurations.put(cacheName, configuration);
        }
    }

    @Override
    @NonNull
    protected RedisCache createRedisCache(@NonNull
            String name, @Nullable RedisCacheConfiguration cacheConfig) {
        RedisCacheConfiguration config = initialCacheConfigurations.get(name);
        if (config == null) {
            config = cacheConfig != null ? cacheConfig : defaultCacheConfiguration;
        }
        // 使用父类的方法创建 RedisCache 实例
        return super.createRedisCache(name, config);
    }
}
