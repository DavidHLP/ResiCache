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

	String distributedLockName() default "";

	boolean internalLock() default false;

	boolean cacheNullValues() default false;

	boolean useBloomFilter() default false;

	boolean randomTtl() default false;

	float variance() default 0.2f;
}