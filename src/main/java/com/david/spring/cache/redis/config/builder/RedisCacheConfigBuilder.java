package com.david.spring.cache.redis.config.builder;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis缓存配置建造者
 * 使用建造者模式来构建复杂的缓存配置
 */
@Slf4j
public class RedisCacheConfigBuilder {

    /**
     * 创建新的配置建造者
     */
    public static RedisCacheConfigBuilder create() {
        return new RedisCacheConfigBuilder();
    }

    private Duration defaultTtl = Duration.ofMinutes(60);
    private boolean allowNullValues = true;
    private boolean enableTransactions = false;
    private boolean enableStatistics = false;
    private int connectionPoolSize = 64;
    private int connectionMinimumIdleSize = 10;
    private int connectTimeout = 10000;
    private int idleTimeout = 10000;
    private int commandTimeout = 3000;
    private int retryAttempts = 3;
    private int retryInterval = 1500;
    private final Map<String, CacheConfig> cacheConfigurations = new HashMap<>();

    /**
     * 设置默认TTL
     */
    public RedisCacheConfigBuilder defaultTtl(Duration ttl) {
        this.defaultTtl = ttl;
        return this;
    }

    /**
     * 设置是否允许空值
     */
    public RedisCacheConfigBuilder allowNullValues(boolean allow) {
        this.allowNullValues = allow;
        return this;
    }

    /**
     * 启用事务支持
     */
    public RedisCacheConfigBuilder enableTransactions() {
        this.enableTransactions = true;
        return this;
    }

    /**
     * 启用统计功能
     */
    public RedisCacheConfigBuilder enableStatistics() {
        this.enableStatistics = true;
        return this;
    }

    /**
     * 配置连接池大小
     */
    public RedisCacheConfigBuilder connectionPoolSize(int poolSize) {
        this.connectionPoolSize = poolSize;
        return this;
    }

    /**
     * 配置最小空闲连接数
     */
    public RedisCacheConfigBuilder connectionMinimumIdleSize(int idleSize) {
        this.connectionMinimumIdleSize = idleSize;
        return this;
    }

    /**
     * 配置连接超时时间
     */
    public RedisCacheConfigBuilder connectTimeout(int timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    /**
     * 配置空闲超时时间
     */
    public RedisCacheConfigBuilder idleTimeout(int timeout) {
        this.idleTimeout = timeout;
        return this;
    }

    /**
     * 配置命令超时时间
     */
    public RedisCacheConfigBuilder commandTimeout(int timeout) {
        this.commandTimeout = timeout;
        return this;
    }

    /**
     * 配置重试次数
     */
    public RedisCacheConfigBuilder retryAttempts(int attempts) {
        this.retryAttempts = attempts;
        return this;
    }

    /**
     * 配置重试间隔
     */
    public RedisCacheConfigBuilder retryInterval(int interval) {
        this.retryInterval = interval;
        return this;
    }

    /**
     * 添加特定缓存配置
     */
    public CacheConfigBuilder cache(String cacheName) {
        return new CacheConfigBuilder(this, cacheName);
    }

    /**
     * 构建配置
     */
    public RedisCacheConfig build() {
        log.info("Building Redis cache configuration with {} cache-specific configs", cacheConfigurations.size());

        return RedisCacheConfig.builder()
                .defaultTtl(defaultTtl)
                .allowNullValues(allowNullValues)
                .enableTransactions(enableTransactions)
                .enableStatistics(enableStatistics)
                .connectionPoolSize(connectionPoolSize)
                .connectionMinimumIdleSize(connectionMinimumIdleSize)
                .connectTimeout(connectTimeout)
                .idleTimeout(idleTimeout)
                .commandTimeout(commandTimeout)
                .retryAttempts(retryAttempts)
                .retryInterval(retryInterval)
                .cacheConfigurations(new HashMap<>(cacheConfigurations))
                .build();
    }

    /**
     * 缓存配置建造者
     */
    public static class CacheConfigBuilder {
        private final RedisCacheConfigBuilder parent;
        private final String cacheName;
        private Duration ttl;
        private boolean allowNullValues = true;
        private boolean enablePreRefresh = false;
        private double preRefreshThreshold = 0.2;
        private boolean useBloomFilter = false;
        private boolean enableRandomTtl = false;
        private float ttlVariance = 0.1f;

        public CacheConfigBuilder(RedisCacheConfigBuilder parent, String cacheName) {
            this.parent = parent;
            this.cacheName = cacheName;
        }

        public CacheConfigBuilder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public CacheConfigBuilder allowNullValues(boolean allow) {
            this.allowNullValues = allow;
            return this;
        }

        public CacheConfigBuilder enablePreRefresh() {
            this.enablePreRefresh = true;
            return this;
        }

        public CacheConfigBuilder preRefreshThreshold(double threshold) {
            this.preRefreshThreshold = threshold;
            return this;
        }

        public CacheConfigBuilder useBloomFilter() {
            this.useBloomFilter = true;
            return this;
        }

        public CacheConfigBuilder enableRandomTtl() {
            this.enableRandomTtl = true;
            return this;
        }

        public CacheConfigBuilder ttlVariance(float variance) {
            this.ttlVariance = variance;
            return this;
        }

        public RedisCacheConfigBuilder and() {
            CacheConfig config = CacheConfig.builder()
                    .ttl(ttl != null ? ttl : parent.defaultTtl)
                    .allowNullValues(allowNullValues)
                    .enablePreRefresh(enablePreRefresh)
                    .preRefreshThreshold(preRefreshThreshold)
                    .useBloomFilter(useBloomFilter)
                    .enableRandomTtl(enableRandomTtl)
                    .ttlVariance(ttlVariance)
                    .build();

            parent.cacheConfigurations.put(cacheName, config);
            log.debug("Added cache config for '{}': TTL={}, preRefresh={}, bloomFilter={}",
                    cacheName, ttl, enablePreRefresh, useBloomFilter);
            return parent;
        }
    }
}