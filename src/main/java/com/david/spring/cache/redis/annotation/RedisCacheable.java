package com.david.spring.cache.redis.annotation;

import com.david.spring.cache.redis.core.writer.support.PreRefreshMode;

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

    // TODO
    boolean useSecondLevelCache() default false;

    boolean cacheNullValues() default false;

    boolean useBloomFilter() default false;

    boolean randomTtl() default false;

    float variance() default 0.2F;

    /** 是否启用预刷新 当缓存接近过期时（剩余TTL低于阈值），触发预刷新机制 */
    boolean enablePreRefresh() default false;

    /** 预刷新阈值（0-1之间） 例如：0.3 表示当剩余TTL低于30%时触发预刷新 默认值0.3，即剩余30%时触发 */
    double preRefreshThreshold() default 0.3;

    /** 预刷新模式 SYNC: 同步模式，返回null触发缓存未命中，让调用者重新加载数据 ASYNC: 异步模式，返回旧值给用户，同时在后台异步刷新缓存（需要在拦截器层面支持） */
    PreRefreshMode preRefreshMode() default PreRefreshMode.SYNC;
}
