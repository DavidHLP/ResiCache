package com.david.spring.cache.redis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CacheGuard配置属性
 * 用于外化配置项，提供运维友好的配置管理
 */
@Data
@Component
@ConfigurationProperties(prefix = "cache.guard")
public class CacheGuardProperties {

    /**
     * 双删延迟时间（毫秒）
     */
    private long doubleDeleteDelayMs = 300L;

    /**
     * 注册表初始容量配置
     */
    private RegistryConfig registry = new RegistryConfig();

    /**
     * 布隆过滤器配置
     */
    private BloomFilterConfig bloomFilter = new BloomFilterConfig();

    /**
     * 缓存清理配置
     */
    private CleanupConfig cleanup = new CleanupConfig();

    /**
     * 监控配置
     */
    private MetricsConfig metrics = new MetricsConfig();

    @Data
    public static class RegistryConfig {
        /**
         * ConcurrentHashMap初始容量
         */
        private int initialCapacity = 256;

        /**
         * ConcurrentHashMap负载因子
         */
        private float loadFactor = 0.75f;

        /**
         * ConcurrentHashMap并发级别
         */
        private int concurrencyLevel = 16;
    }

    @Data
    public static class BloomFilterConfig {
        /**
         * 过滤器Map初始容量
         */
        private int filterMapInitialCapacity = 64;

        /**
         * 启用缓存Set初始容量
         */
        private int enabledCacheSetInitialCapacity = 64;

        /**
         * 布隆过滤器前缀
         */
        private String bloomPrefix = "bf:cache:";
    }

    @Data
    public static class CleanupConfig {
        /**
         * 是否启用自动清理
         */
        private boolean enabled = true;

        /**
         * 清理间隔（毫秒）
         */
        private long intervalMs = 3600000L; // 1小时

        /**
         * 锁最大空闲时间（毫秒）
         */
        private long lockMaxIdleTimeMs = 1800000L; // 30分钟

        /**
         * 调用信息最大空闲时间（毫秒）
         */
        private long invocationMaxIdleTimeMs = 1800000L; // 30分钟
    }

    @Data
    public static class MetricsConfig {
        /**
         * 是否启用指标监控
         */
        private boolean enabled = true;

        /**
         * 指标前缀
         */
        private String prefix = "cache.guard";

        /**
         * 是否记录详细指标（包含缓存名称标签）
         */
        private boolean detailed = true;
    }
}