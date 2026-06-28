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

    /** Spring 原生注解兼容模式: FULL, NONE, SELECTIVE。默认 SELECTIVE,避免双 Advisor。 */
    private NativeAnnotationMode nativeAnnotationMode = NativeAnnotationMode.SELECTIVE;

    /** 防护链总开关配置(resi-cache.protection.*) */
    private ProtectionProperties protection = new ProtectionProperties();

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

        /**
         * CLEAN 后布隆过滤器的 rebuilding 窗口(秒)。默认 {@code 30};{@code 0} = 禁用。
         *
         * <p>背景(WS-1.2c):CLEAN({@code @CacheEvict(allEntries=true)})清空布隆后,
         * 空布隆对所有 key 判定 {@code mightContain=false},导致后续 GET 在
         * {@code RedisProCache.get(key, loader)} 的前置短路处<b>静默返回 null</b>
         * (既不查缓存也不调 loader),违反 Spring {@code @Cacheable}"miss 即调 loader
         * 返回真实值"的契约 —— 是数据正确性缺陷而非 DB 击穿(loader 未被调用)。
         *
         * <p>启用后,{@link io.github.davidhlp.spring.cache.redis.protection.bloom.BloomSupport#clear}
         * 清空过滤器的同时在 Redis 写入一个 per-cacheName 的 rebuilding 标志(TTL=本窗口),
         * 期间 {@code mightContain} <b>fail-open</b>(一律返回 true),让请求越过 bloom 短路、
         * 走正常 sync 锁 + loader 路径,返回 DB 真实值并由 PUT 回填重建布隆。窗口由 Redis
         * TTL 自动结束,无需猜测重建 key 数量。标志走 Redis 以保证 Cluster 多实例一致
         * (容忍秒级 local 缓存延迟)。
         *
         * <p>{@code 0} 禁用 = 保持 v0.0.x 旧行为(向后兼容),但保留静默 null 缺陷。
         */
        private long rebuildWindowSeconds = 30;
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

        /**
         * 是否显式降级为单 JVM synchronized(无分布式锁后端时).
         *
         * <p>默认 {@code false}:声明 {@code sync=true} 但无分布式 LockManager bean
         * (如 Redisson 缺失)时,ResiCache <b>绝不静默</b>退化为单 JVM —— 而是启动期告警 +
         * 运行期 fail-fast(首次未命中即抛 {@link IllegalStateException})。多实例部署下,
         * 单 JVM synchronized 无法防击穿,是最坏失败模式。
         *
         * <p>设为 {@code true} 显式接受单 JVM 同步作为合法降级(单实例部署或测试场景),
         * 此时仍保证 JVM 内线程互斥,但 ResiCache 会发出 {@code protection.degraded=local-only}
         * 告警使安全属性可观测(WS-1.4 升级为链级 Observation 事件)。
         *
         * @see io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport
         */
        private boolean localOnly = false;
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

    /**
     * 防护链配置.
     *
     * <p>当 {@code enabled=false} 时,短路掉防护纵深 handler(布隆/锁/提前过期/空值),
     * 但<b>保留 TTL</b>——TtlHandler 兼担基础 TTL 计算,禁用会导致永久缓存。即:关闭后
     * 缓存仍按 TTL 正常过期,只是失去防穿透/击穿/雪崩/热 key 能力。
     *
     * <p><b>仅启动时生效</b>:责任链单例缓存于首次构建,运行时变更此属性需重启应用。
     * <b>Blast radius</b>:类级开关关闭整个 {@code RedisCacheAutoConfiguration},
     * 含 SecureJackson RedisTemplate(序列化器将回退 Spring 默认)。
     */
    @Getter
    @Setter
    public static class ProtectionProperties {
        /** 是否启用防护链(布隆/锁/提前过期/空值;TTL 始终保留)。默认 true。 */
        private boolean enabled = true;
    }
}
