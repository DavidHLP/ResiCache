package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.Caching;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Caching
public @interface RedisCaching {

    RedisCacheable[] cacheable() default {};

    RedisCacheEvict[] evict() default {};
}
