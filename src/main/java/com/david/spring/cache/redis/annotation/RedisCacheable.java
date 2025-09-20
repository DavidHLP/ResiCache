package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Cacheable
public @interface RedisCacheable {

	@AliasFor(annotation = Cacheable.class, attribute = "value")
	String[] value() default {};

	@AliasFor(annotation = Cacheable.class, attribute = "cacheNames")
	String[] cacheNames() default {};

	@AliasFor(annotation = Cacheable.class)
	String key() default "";

	@AliasFor(annotation = Cacheable.class)
	String keyGenerator() default "";

	@AliasFor(annotation = Cacheable.class)
	String cacheManager() default "redisProCacheManager";

	@AliasFor(annotation = Cacheable.class)
	String cacheResolver() default "";

	@AliasFor(annotation = Cacheable.class)
	String condition() default "";

	@AliasFor(annotation = Cacheable.class)
	String unless() default "";

	@AliasFor(annotation = Cacheable.class)
	boolean sync() default false;

	long ttl() default 60;

	Class<?> type() default Object.class;

	boolean useSecondLevelCache() default false;

	boolean distributedLock() default false;

	boolean internalLock() default false;

	boolean cacheNullValues() default false;

	boolean useBloomFilter() default false;

	boolean randomTtl() default false;

	float variance() default 0.2f;

	/**
	 * 缓存获取策略类型
	 */
	String fetchStrategy() default "SIMPLE";

	/**
	 * 是否启用预刷新
	 */
	boolean enablePreRefresh() default false;

	/**
	 * 预刷新阈值百分比（当剩余TTL低于总TTL的此百分比时触发）
	 */
	double preRefreshThreshold() default 0.3;

	/**
	 * 分布式锁名称
	 */
	String distributedLockName() default "";

	/**
	 * 自定义策略类名
	 */
	String customStrategyClass() default "";
}

