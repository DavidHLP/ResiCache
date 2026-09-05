package io.github.davidhlp.spring.cache.redis.config;




import io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * ResiCache 配置属性类
 *
 * <p>提供可外部化的配置项，支持在 application.yml 中配置：
 * <pre>
 * resi-cache:
 *   default-ttl: 30m
 *   early-expiration:
 *     pool-size: 2
 *     max-pool-size: 10
 *     queue-capacity: 100
 * </pre>
 *
 * <p>P1-CONFIG-001:所有 nested properties 均带 {@code @Valid @NotNull}(绑定期 cascade
 * 校验),数值字段带 Jakarta 约束;跨字段关系(redis mode/sentinel/cluster/tls)由类级
 * validator 承担并把 violation 绑定到具体 property node。非法配置在绑定完成时一次性失败。
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

    /** 布隆过滤器参数(resi-cache.bloom.*) */
    @Valid
    @NotNull
    private BloomProperties bloom = new BloomProperties();

    /** 提前过期配置 */
    @Valid
    @NotNull
    private EarlyExpirationProperties earlyExpiration = new EarlyExpirationProperties();

    /** 同步锁配置 */
    @Valid
    @NotNull
    private SyncLockProperties syncLock = new SyncLockProperties();

    /** Redisson 连接池配置 */
    @Valid
    @NotNull
    private RedissonProperties redisson = new RedissonProperties();

    /** Redis 部署配置 */
    @Valid
    @NotNull
    private RedisDeploymentProperties redis = new RedisDeploymentProperties();

    /** 序列化器配置 */
    @Valid
    @NotNull
    private SerializerProperties serializer = new SerializerProperties();

    /** 按缓存名称细粒度配置 */
    private Map<String, CacheConfig> caches = new HashMap<>();

    /** 禁用的 Handler 列表（如 bloom-filter、early-expiration、sync-lock） */
    private List<String> disabledHandlers = new ArrayList<>();

    /** Spring 原生注解兼容模式: FULL, NONE, SELECTIVE。默认 SELECTIVE,避免双 Advisor。 */
    private NativeAnnotationMode nativeAnnotationMode = NativeAnnotationMode.SELECTIVE;

    /** 防护链总开关配置(resi-cache.protection.*) */
    @Valid
    @NotNull
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
     * 布隆过滤器参数(resi-cache.bloom.*)。
     *
     * <p>P1-CONFIG-001:把原先经 {@code @Value} 散读的 {@code resi-cache.bloom.*}
     * 收口到 properties 统一模型,启动期一次性校验。
     */
    @Getter
    @Setter
    public static class BloomProperties {
        /** Redis 布隆位图 key 前缀 */
        private String prefix = "bf:";

        /** 位图大小(bits) */
        @Min(1)
        private int bitSize = 8_388_608;

        /** hash 函数个数 */
        @Min(1)
        @Max(32)
        private int hashFunctions = 3;

        /** hash 位置本地缓存上限 */
        @Min(1)
        private int hashCacheSize = 10_000;
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
        /** 启动期序列化 pre-flight 探测(guide §115):采样 N keys 检测非 envelope → WARN。默认关闭(opt-in;扫描 Redis 是启动副作用)。 */
        private boolean probeEnabled = false;
        /** pre-flight 探测的采样 key 数上限(默认 100)。 */
        @Min(1)
        private int probeSampleSize = 100;
        /** operator CLI migration workflow settings; never auto-executed at application startup. */
        @Valid
        @NotNull
        private SerializationMigrationProperties migration =
                new SerializationMigrationProperties();
    }

    /**
     * Redis deployment configuration.
     *
     * <p>跨字段关系(mode 决定 host/port vs clusterNodes vs sentinelNodes;tlsRequired
     * 依赖 tlsEnabled)由 {@link RedisDeploymentValidator} 类级校验,违规绑定到具体字段。
     */
    @Getter
    @Setter
    @RedisDeploymentValidator
    public static class RedisDeploymentProperties {
        /** 部署模式: single, cluster, sentinel */
        private String mode = "single";
        /** 主机地址（单节点模式） */
        private String host = "localhost";
        /** 端口（单节点模式） */
        @Min(1)
        @Max(65535)
        private int port = 6379;
        /** 用户名（ACL） */
        private String username;
        /** 密码 */
        private String password;
        /** 数据库索引 */
        @Min(0)
        private int database = 0;
        /** 是否启用 TLS */
        private boolean tlsEnabled = false;
        /**
         * 强制 TLS —— 当为 {@code true} 时,启动期会校验
         * {@link #tlsEnabled} 必须为 {@code true},否则抛 {@link IllegalStateException}
         * fail-fast。生产推荐设为 {@code true}。
         */
        private boolean tlsRequired = false;
        /** 集群节点地址列表 */
        private List<String> clusterNodes = new ArrayList<>();
        /** Sentinel 主节点名称 */
        private String sentinelMaster;
        /** Sentinel 节点地址列表 */
        private List<String> sentinelNodes = new ArrayList<>();
        /** Redisson YAML 配置文件路径（高级配置） */
        private String redissonConfigPath;
    }


    @Getter
    @Setter
    public static class EarlyExpirationProperties {
        /** 核心线程池大小 */
        @Min(1)
        private int poolSize = 2;
        /** 最大线程池大小 */
        @Min(1)
        private int maxPoolSize = 10;
        /** 队列容量 */
        @Min(1)
        private int queueCapacity = 100;
    }

    @Getter
    @Setter
    public static class SyncLockProperties {
        /** 同步锁超时时间 */
        @Min(0)
        private long timeout = 3000;
        /** 超时时间单位 */
        @NotNull
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
         * 告警使安全属性可观测。
         *
         * @see io.github.davidhlp.spring.cache.redis.cache.SyncSupport
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
      * <b>Blast radius</b>:类级开关只短路防护纵深 handler(布隆/锁/提前过期/空值,经
      * {@code CacheHandlerChainFactory.resolveProtectionDisabled} 解析),
      * <b>不</b>关闭整个 {@code RedisCacheAutoConfiguration} —— 后者由 {@code resi-cache.enabled}
      * 门控(见 {@link RedisCacheAutoConfiguration})。TTL handler 不在此开关管辖(兼担基础 TTL 计算)。
     */
    @Getter
    @Setter
    public static class ProtectionProperties {
        /** 是否启用防护链(布隆/锁/提前过期/空值;TTL 始终保留)。默认 true。 */
        private boolean enabled = true;

        /**
         * per-mechanism 启动时静态覆盖(分项开关)。
         * <p>每个字段 {@code null} = 继承 {@link #enabled} 总开关;非 {@code null}
         * = 启动时静态覆盖该机制。总开关为 {@code false} 时分项 {@code true} 不能
         * 重新启用(总开关优先);分项 {@code false} 只能进一步关闭对应机制。
         * <p><b>仅启动时生效</b>:责任链单例缓存于首次 {@code createChain} 构建,
         * 修改配置需重启应用,不存在运行时热更新或链重建。
         * <p>对应 handler: bloom-filter / sync-lock / early-expiration / null-value。
         * <p>TTL 不在此列(TtlHandler 兼担基础 TTL 计算,关闭会导致永久缓存,见
         * {@code CacheHandlerChainFactory} 注释)。
         */
        private Boolean bloomFilterEnabled = null;
        private Boolean syncLockEnabled = null;
        private Boolean earlyExpirationEnabled = null;
        private Boolean nullValueEnabled = null;
    }
}
