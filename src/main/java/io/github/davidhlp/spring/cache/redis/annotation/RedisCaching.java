package io.github.davidhlp.spring.cache.redis.annotation;

import java.lang.annotation.*;

/**
 * 复合缓存注解.
 *
 * <p>用于在一个方法上同时定义多个缓存操作.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RedisCaching {

    /**
     * 可缓存的操作数组.
     */
    RedisCacheable[] redisCacheable() default {};

    /**
     * 缓存清除的操作数组.
     */
    RedisCacheEvict[] redisCacheEvict() default {};

    /**
     * 缓存更新的操作数组.
     */
    RedisCachePut[] redisCachePut() default {};
}
