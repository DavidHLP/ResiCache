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

	@AliasFor(annotation = CacheEvict.class, attribute = "key")
	String key() default "";

	@AliasFor(annotation = CacheEvict.class, attribute = "keyGenerator")
	String keyGenerator() default "";

	@AliasFor(annotation = CacheEvict.class, attribute = "cacheManager")
	String cacheManager() default "";

	@AliasFor(annotation = CacheEvict.class, attribute = "cacheResolver")
	String cacheResolver() default "";

	@AliasFor(annotation = CacheEvict.class, attribute = "condition")
	String condition() default "";

	@AliasFor(annotation = CacheEvict.class, attribute = "allEntries")
	boolean allEntries() default false;

	@AliasFor(annotation = CacheEvict.class, attribute = "beforeInvocation")
	boolean beforeInvocation() default false;

	boolean sync() default false;
}
