package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@CacheEvict
public @interface RedisCacheEvict {

	@AliasFor(annotation = CacheEvict.class, attribute = "value")
	String[] value() default {};

	@AliasFor(annotation = CacheEvict.class, attribute = "cacheNames")
	String[] cacheNames() default {};

	@AliasFor(annotation = CacheEvict.class)
	String key() default "";

	@AliasFor(annotation = CacheEvict.class)
	String keyGenerator() default "";

	@AliasFor(annotation = CacheEvict.class)
	String cacheManager() default "redisProCacheManager";

	@AliasFor(annotation = CacheEvict.class)
	String cacheResolver() default "";

	@AliasFor(annotation = CacheEvict.class)
	String condition() default "";

	@AliasFor(annotation = CacheEvict.class)
	boolean allEntries() default false;

	@AliasFor(annotation = CacheEvict.class)
	boolean beforeInvocation() default false;

	boolean sync() default false;
}
