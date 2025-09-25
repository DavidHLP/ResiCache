package com.david.spring.cache.redis.config.builder;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * 单个缓存配置
 */
@Data
@Builder
public class CacheConfig {
    private Duration ttl;
    private boolean allowNullValues;
    private boolean enablePreRefresh;
    private double preRefreshThreshold;
    private boolean useBloomFilter;
    private boolean enableRandomTtl;
    private float ttlVariance;
}