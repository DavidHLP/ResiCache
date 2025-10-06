package com.david.spring.cache.redis.annotation;

import com.david.spring.cache.redis.core.writer.support.refresh.PreRefreshMode;

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
     * Whether to guard cache miss/reload sections with a fine-grained synchronization lock.
     * Only the critical regeneration path is locked, leaving cache hits unaffected.
     */
    boolean sync() default false;

    /** Timeout for acquiring sync locks (seconds). */
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
