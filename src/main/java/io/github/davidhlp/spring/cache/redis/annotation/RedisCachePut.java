package io.github.davidhlp.spring.cache.redis.annotation;




import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import jakarta.validation.constraints.PositiveOrZero;
import java.lang.annotation.*;

/**
 * 缓存更新注解.
 *
 * <p>用于标注方法，每次执行方法后强制更新缓存，而非检查缓存是否存在。
 * 适用于数据更新后需要立即刷新缓存的场景。
 *
 * <p>与 @RedisCacheable 的区别：
 * <ul>
 *   <li>@RedisCacheable：先检查缓存，缓存命中则返回缓存值</li>
 *   <li>@RedisCachePut：每次都执行方法并更新缓存</li>
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCachePut {

    /**
     * 缓存名称，与 Spring Cache 的 value 相同.
     *
     * <p>同时声明 {@code value} 与 {@link #cacheNames()} 时 <b>{@code value} 优先</b>；
     * 只声明其中一个时按声明的那个解析（见 {@code COMPATIBILITY.md} 的注解属性解析规则）。
     */
    String[] value() default {};

    /**
     * 缓存名称别名，与 value 相同.
     *
     * <p>同时声明 {@link #value()} 时由 {@code value} 决定——本属性只在其为空时生效。
     */
    String[] cacheNames() default {};

    /**
     * 缓存 key，支持 SpEL 表达式.
     */
    String key() default "";

    /**
     * 自定义 key 生成器 bean 名称.
     */
    String keyGenerator() default "";

    /**
     * 自定义 cacheManager bean 名称.
     */
    String cacheManager() default "";

    /**
     * 自定义 cacheResolver bean 名称.
     */
    String cacheResolver() default "";

    /**
     * 缓存条件，支持 SpEL 表达式.
     */
    String condition() default "";

    /**
     * 不缓存的条件，支持 SpEL 表达式.
     */
    String unless() default "";

    /**
     * 缓存过期时间（秒）.
     *
     * <p>{@code 0}（即未设置）表示不作方法级 TTL 声明：该方法的条目改用 cache 级
     * {@code resi-cache.default-ttl}（默认 30 分钟，{@code caches.*.ttl} 可覆盖）。
     * 只有大于 0 的值才覆盖该配置默认值。与 {@link RedisCacheEvict#ttl()} 的未设置
     * 编码一致。
     */
    long ttl() default 0;

    /**
     * 缓存值的声明类型（兼容性元数据）.
     *
     * <p>当前运行时不基于此属性强制转换或校验；实际类型由返回值与序列化器决定.
     */
    Class<?> type() default Object.class;

    /**
     * 是否缓存空值防止缓存穿透.
     */
    boolean cacheNullValues() default false;

    /**
     * 是否使用布隆过滤器防缓存穿透.
     */
    boolean useBloomFilter() default false;

    /**
     * 布隆过滤器预期插入数量.
     */
    @PositiveOrZero
    long expectedInsertions() default 100000L;

    /**
     * 布隆过滤器误判率.
     */
    double falseProbability() default 0.01;

    /**
     * 是否使用分布式锁防缓存击穿.
     */
    boolean sync() default false;

    /**
     * 同步锁超时时间（秒）.
     */
    long syncTimeout() default 10;

    /**
     * 是否使用 TTL 随机化防缓存雪崩.
     */
    boolean randomTtl() default false;

    /**
     * TTL 随机化范围.
     */
    float variance() default 0.2F;

    /**
     * 是否启用提前过期.
     */
    boolean enableEarlyExpiration() default false;

    /**
     * 提前过期阈值.
     */
    double earlyExpirationThreshold() default 0.3;

    /**
     * 提前过期模式.
     */
    EarlyExpirationMode earlyExpirationMode() default EarlyExpirationMode.SYNC;
}
