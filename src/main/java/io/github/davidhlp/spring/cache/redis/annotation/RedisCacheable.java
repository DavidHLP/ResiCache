package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCacheable {

    String[] value() default {};

    String[] cacheNames() default {};

    String key() default "";

    String keyGenerator() default "";

    String cacheManager() default "";

    String cacheResolver() default "";

    String condition() default "";

    String unless() default "";

    /**
     * 是否使用细粒度同步锁保护缓存未命中/重新加载部分。
     * 仅锁定关键的重新生成路径，缓存命中不受影响。
     */
    boolean sync() default false;


    /** 获取同步锁的超时时间（秒）。 */
    long syncTimeout() default 10;

    long ttl() default 60;

    Class<?> type() default Object.class;

    boolean cacheNullValues() default false;

    boolean useBloomFilter() default false;

    boolean randomTtl() default false;

    float variance() default 0.2F;

    boolean enablePreRefresh() default false;

    double preRefreshThreshold() default 0.3;

    PreRefreshMode preRefreshMode() default PreRefreshMode.SYNC;

    boolean useSecondLevelCache() default false;
}
