package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;

import java.lang.annotation.*;

/**
 * 缓存可用的注解.
 *
 * <p>用于标注方法，使该方法的返回结果被缓存。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCacheable {

    /**
     * 缓存名称，与 Spring Cache 的 value 相同.
     */
    String[] value() default {};

    /**
     * 缓存名称别名，与 value 相同.
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
     * 是否使用细粒度同步锁保护缓存未命中/重新加载部分.
     */
    boolean sync() default false;

    /**
     * 获取同步锁的超时时间（秒）.
     */
    long syncTimeout() default 10;

    /**
     * 缓存过期时间（秒）.
     */
    long ttl() default 60;

    /**
     * 缓存值的类型.
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
     * 是否使用 TTL 随机化防缓存雪崩.
     */
    boolean randomTtl() default false;

    /**
     * TTL 随机化范围.
     */
    float variance() default 0.2F;

    /**
     * 是否启用预刷新.
     */
    boolean enablePreRefresh() default false;

    /**
     * 预刷新阈值.
     */
    double preRefreshThreshold() default 0.3;

    /**
     * 预刷新模式.
     */
    PreRefreshMode preRefreshMode() default PreRefreshMode.SYNC;
}
