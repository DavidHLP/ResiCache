package io.github.davidhlp.spring.cache.redis.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   early-expiration:
 *     enabled: true
 *     pool-size: 2
 *     max-pool-size: 10
 *     queue-capacity: 100
 * </pre>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "resi-cache")
public class RedisProCacheProperties {

    /** 默认缓存TTL */
    @NotNull
    private Duration defaultTtl = Duration.ofMinutes(30);

    /** SpEL 求值失败时是否抛出异常（默认 true）。配置错误（语法错误）始终抛出。 */
    private boolean failOnSpelError = true;

    /** 是否启用事务感知缓存 */
    private boolean transactionAware = false;

    /** 全局缓存键前缀 */
    private String keyPrefix = "";

    /** 布隆过滤器配置 */
    private BloomFilterProperties bloomFilter = new BloomFilterProperties();

    /** 提前过期配置 */
    private EarlyExpirationProperties earlyExpiration = new EarlyExpirationProperties();

    /** 同步锁配置 */
    private SyncLockProperties syncLock = new SyncLockProperties();

    /** Redisson 连接池配置 */
    private RedissonProperties redisson = new RedissonProperties();

    /** Redis 部署配置 */
    private RedisDeploymentProperties redis = new RedisDeploymentProperties();

    /** 序列化器配置 */
    private SerializerProperties serializer = new SerializerProperties();

    /** 按缓存名称细粒度配置 */
    private Map<String, CacheConfig> caches = new HashMap<>();

    /** 禁用的 Handler 列表（如 bloom-filter、early-expiration、sync-lock） */
    private List<String> disabledHandlers = new ArrayList<>();

    /** Handler 配置（支持按 cacheName 细粒度禁用） */
    private Map<String, HandlerConfig> handlerSettings = new HashMap<>();

    /** Spring 原生注解兼容模式: FULL, NONE, SELECTIVE */
    private NativeAnnotationMode nativeAnnotationMode = NativeAnnotationMode.FULL;

    /**
     * Spring 原生注解兼容模式.
     */
    public enum NativeAnnotationMode {
        /** 转换所有 Spring 原生缓存注解 */
        FULL,
        /** 忽略 Spring 原生缓存注解 */
        NONE,
        /** 仅当同时存在 ResiCache 注解时才转换 */
        SELECTIVE
    }

    /**
     * Per-cache configuration.
     */
    @Getter
    @Setter
    public static class CacheConfig {
        /** 缓存过期时间 */
        private Duration ttl;
        /** 是否缓存空值 */
        private Boolean cacheNullValues;
        /** 缓存键前缀 */
        private String keyPrefix;
        /** 是否启用布隆过滤器 */
        private Boolean enableBloomFilter;
        /** 是否启用提前过期 */
        private Boolean enableEarlyExpiration;
    }

    /**
     * Serializer configuration.
     */
    @Getter
    @Setter
    public static class SerializerProperties {
        /** 允许的反序列化包前缀列表 */
        private List<String> allowedPackagePrefixes = new ArrayList<>(
                List.of("io.github.davidhlp"));
        /** 遇到未知类型时是否失败 */
        private boolean failOnUnknownType = true;
        /** Jackson 类型属性名 */
        private String typeProperty = "@class";
        /** 是否启用 Jackson 多态类型信息（默认关闭，更安全） */
        private boolean polymorphicTypingEnabled = false;
    }

    /**
     * Redis deployment configuration.
     */
    @Getter
    @Setter
    public static class RedisDeploymentProperties {
        /** 部署模式: single, cluster, sentinel */
        private String mode = "single";
        /** 主机地址（单节点模式） */
        private String host = "localhost";
        /** 端口（单节点模式） */
        private int port = 6379;
        /** 用户名（ACL） */
        private String username;
        /** 密码 */
        private String password;
        /** 数据库索引 */
        private int database = 0;
        /** 是否启用 TLS */
        private boolean tlsEnabled = false;
        /** 集群节点地址列表 */
        private List<String> clusterNodes = new ArrayList<>();
        /** Sentinel 主节点名称 */
        private String sentinelMaster;
        /** Sentinel 节点地址列表 */
        private List<String> sentinelNodes = new ArrayList<>();
        /** Redisson YAML 配置文件路径（高级配置） */
        private String redissonConfigPath;
    }

    /**
     * Handler 配置类
     * 支持按缓存名称细粒度禁用特定的 Handler
     */
    @Getter
    @Setter
    public static class HandlerConfig {
        /** 禁用的 Handler 列表 */
        private List<String> disabledHandlers = new ArrayList<>();
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
    public static class EarlyExpirationProperties {
        /** 是否启用提前过期 */
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
