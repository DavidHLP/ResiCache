package com.david.spring.cache.redis.config.builder;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.Map;

/**
 * Redis缓存完整配置
 */
@Data
@Builder
public class RedisCacheConfig {
    private Duration defaultTtl;
    private boolean allowNullValues;
    private boolean enableTransactions;
    private boolean enableStatistics;
    private int connectionPoolSize;
    private int connectionMinimumIdleSize;
    private int connectTimeout;
    private int idleTimeout;
    private int commandTimeout;
    private int retryAttempts;
    private int retryInterval;
    private Map<String, CacheConfig> cacheConfigurations;

    /**
     * 创建默认配置
     */
    public static RedisCacheConfig defaultConfig() {
        return new RedisCacheConfigBuilder()
                .defaultTtl(Duration.ofMinutes(60))
                .allowNullValues(true)
                .connectionPoolSize(64)
                .build();
    }

    /**
     * 获取特定缓存的配置
     */
    public CacheConfig getCacheConfig(String cacheName) {
        return cacheConfigurations.get(cacheName);
    }
}