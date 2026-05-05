package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;

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
     * 不清除的条件，支持 SpEL 表达式.
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

    /**
     * 是否使用分布式锁防缓存击穿.
     */
    boolean sync() default false;

    /**
     * 同步锁超时时间（秒）.
     */
    long syncTimeout() default -1;

    /**
     * 缓存过期时间（秒）.
     */
    long ttl() default 0;

    /**
     * 是否使用布隆过滤器防缓存穿透.
     */
    boolean useBloomFilter() default false;

    /**
     * 布隆过滤器预期插入数量.
     */
    long expectedInsertions() default 100000;

    /**
     * 布隆过滤器误判率.
     */
    double falseProbability() default 0.01;

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
