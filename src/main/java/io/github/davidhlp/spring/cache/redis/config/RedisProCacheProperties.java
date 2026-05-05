package io.github.davidhlp.spring.cache.redis.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.validation.constraints.Min;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * ResiCache 配置属性类
 *
 * <p>提供可外部化的配置项，支持在 application.yml 中配置：
 * <pre>
 * resi-cache:
 *   default-ttl: 30m
 *   bloom-filter:
 *     enabled: true
 *     expected-insertions: 100000
 *     false-probability: 0.01
 *   pre-refresh:
 *     enabled: true
 *     pool-size: 2
 *     max-pool-size: 10
 *     queue-capacity: 100
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "resi-cache")
public class RedisProCacheProperties {

    /** 默认缓存TTL */
    private Duration defaultTtl = Duration.ofMinutes(30);

    /** SpEL 求值失败时是否抛出异常（默认 true）。配置错误（语法错误）始终抛出。 */
    private boolean failOnSpelError = true;

    /** 布隆过滤器配置 */
    private BloomFilterProperties bloomFilter = new BloomFilterProperties();

    /** 预刷新配置 */
    private PreRefreshProperties preRefresh = new PreRefreshProperties();

    /** 同步锁配置 */
    private SyncLockProperties syncLock = new SyncLockProperties();

    /** Redisson 连接池配置 */
    private RedissonProperties redisson = new RedissonProperties();

    /** 禁用的 Handler 列表（如 bloom-filter、pre-refresh、sync-lock） */
    private java.util.List<String> disabledHandlers = new java.util.ArrayList<>();

    /** Handler 配置（支持按 cacheName 细粒度禁用） */
    private java.util.Map<String, HandlerConfig> handlerSettings = new java.util.HashMap<>();

    /**
     * Handler 配置类
     * 支持按缓存名称细粒度禁用特定的 Handler
     */
    @Getter
    @Setter
    public static class HandlerConfig {
        /** 禁用的 Handler 列表 */
        private java.util.List<String> disabledHandlers = new java.util.ArrayList<>();
    }

    @Getter
    @Setter
    public static class BloomFilterProperties {
        /** 是否启用布隆过滤器 */
        private boolean enabled = true;
        /** 预期插入数量 */
        private long expectedInsertions = 100000;
        /** 期望的误判率 */
        private double falseProbability = 0.01;
        /** 本地哈希缓存最大条目数（每个缓存实例） */
        private int hashCacheSize = 10_000;
    }

    @Getter
    @Setter
    public static class PreRefreshProperties {
        /** 是否启用预刷新 */
        private boolean enabled = true;
        /** 核心线程池大小 */
        private int poolSize = 2;
        /** 最大线程池大小 */
        private int maxPoolSize = 10;
        /** 队列容量 */
        private int queueCapacity = 100;
    }

    @Getter
    @Setter
    public static class SyncLockProperties {
        /** 同步锁超时时间 */
        private long timeout = 3000;
        /** 超时时间单位 */
        private TimeUnit unit = TimeUnit.MILLISECONDS;
        /** 锁键前缀 */
        private String prefix = "cache:lock:";
    }

    @Getter
    @Setter
    public static class RedissonProperties {
        /** 连接池大小 */
        @Min(1)
        private int connectionPoolSize = 64;
        /** 最小空闲连接数 */
        @Min(0)
        private int connectionMinimumIdleSize = 10;
        /** 空闲连接超时时间（毫秒） */
        @Min(1)
        private int idleConnectionTimeout = 10000;
        /** 连接超时时间（毫秒） */
        @Min(1)
        private int connectTimeout = 10000;
        /** 命令超时时间（毫秒） */
        @Min(1)
        private int timeout = 3000;
        /** 重试次数 */
        @Min(1)
        private int retryAttempts = 3;
        /** 重试间隔（毫秒） */
        @Min(1)
        private int retryInterval = 1500;
    }
}
