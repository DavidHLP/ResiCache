package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCacheable {

	@AliasFor(annotation = Cacheable.class, attribute = "value")
	String[] value() default {};

	@AliasFor(annotation = Cacheable.class, attribute = "cacheNames")
	String[] cacheNames() default {};

	@AliasFor(annotation = Cacheable.class, attribute = "key")
	String key() default "";

	@AliasFor(annotation = Cacheable.class, attribute = "keyGenerator")
	String keyGenerator() default "";

	@AliasFor(annotation = Cacheable.class, attribute = "cacheManager")
	String cacheManager() default "";

	@AliasFor(annotation = Cacheable.class, attribute = "cacheResolver")
	String cacheResolver() default "";

	@AliasFor(annotation = Cacheable.class, attribute = "condition")
	String condition() default "";

	@AliasFor(annotation = Cacheable.class, attribute = "unless")
	String unless() default "";

	@AliasFor(annotation = Cacheable.class, attribute = "sync")
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

	boolean enablePreRefresh() default false;

	double preRefreshThreshold() default 0.3;
}

