package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationMode;
import jakarta.validation.constraints.PositiveOrZero;
import java.lang.annotation.*;

/**
 * 缓存清除注解.
 *
 * <p>用于标注方法，在方法执行前或执行后清除缓存。
 * 支持同步清除和异步清除两种模式。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCacheEvict {

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
     * 不清除的条件，支持 SpEL 表达式.
     *
     * <p>为保持公开注解的源/二进制兼容性而保留；当前 Spring CacheEvict 路径没有
     * after-invocation unless 槽位，因此该属性不参与清除判断。需要条件清除时使用
     * {@link #condition()}.
     */
    String unless() default "";

    /**
     * 是否清除所有缓存项.
     */
    boolean allEntries() default false;

    /**
     * 是否在方法执行前清除缓存.
     */
    boolean beforeInvocation() default false;

    /** Compatibility-only sync metadata; eviction has no read/write chain policy. */
    boolean sync() default false;

    /** Compatibility-only syncTimeout metadata; eviction has no read/write chain policy. */
    long syncTimeout() default 10;

    /** Compatibility-only ttl metadata; eviction has no read/write chain policy. */
    long ttl() default 0;

    /** Compatibility-only useBloomFilter metadata; eviction has no read/write chain policy. */
    boolean useBloomFilter() default false;

    /**
     * Compatibility-only Bloom sizing metadata; not consumed by the runtime.
     * Configure {@code resi-cache.bloom.bit-size} and {@code hash-functions} instead.
     */
    @PositiveOrZero
    long expectedInsertions() default 100000L;

    /**
     * Compatibility-only Bloom sizing metadata; not consumed by the runtime.
     * Configure {@code resi-cache.bloom.bit-size} and {@code hash-functions} instead.
     */
    double falseProbability() default 0.01;

    /** Compatibility-only enableEarlyExpiration metadata; eviction has no read/write chain policy. */
    boolean enableEarlyExpiration() default false;

    /** Compatibility-only earlyExpirationThreshold metadata; eviction has no read/write chain policy. */
    double earlyExpirationThreshold() default 0.3;

    /** Compatibility-only earlyExpirationMode metadata; eviction has no read/write chain policy. */
    EarlyExpirationMode earlyExpirationMode() default EarlyExpirationMode.SYNC;
}
