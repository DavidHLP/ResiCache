package com.david.spring.cache.redis.annotation;

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

    boolean sync() default false;

    long ttl() default 60;

    Class<?> type() default Object.class;

    boolean useSecondLevelCache() default false;

    boolean distributedLock() default false;

    boolean internalLock() default false;

    boolean cacheNullValues() default false;

    boolean useBloomFilter() default false;

    boolean randomTtl() default false;

    float variance() default 0.2F;

    boolean enablePreRefresh() default false;

    double preRefreshThreshold() default 0.3;
}
