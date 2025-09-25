package com.david.spring.cache.redis.factory;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * 缓存创建配置
 */
@Data
@Builder
public class CacheCreationConfig {
    private String cacheName;
    private CacheType cacheType;
    private RedisTemplate<String, Object> redisTemplate;
    private Duration defaultTtl;
    private boolean allowNullValues;
    private boolean enableStatistics;
    private boolean enablePreRefresh;
    private double preRefreshThreshold;
    private boolean useBloomFilter;
    private Map<String, Object> additionalProperties;

    /**
     * 获取额外属性
     */
    @SuppressWarnings("unchecked")
    public <T> T getAdditionalProperty(String key, T defaultValue) {
        if (additionalProperties == null) {
            return defaultValue;
        }
        return (T) additionalProperties.getOrDefault(key, defaultValue);
    }
}