package com.david.spring.cache.redis.annotation;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCaching {
	RedisCacheable[] redisCacheable() default {};

	RedisCacheEvict[] redisCacheEvict() default {};
}