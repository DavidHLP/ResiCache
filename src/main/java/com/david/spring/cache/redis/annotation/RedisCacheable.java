package com.david.spring.cache.redis.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Redis缓存注解，用于标记方法的返回值需要被缓存
 *
 * <p>该注解可以应用于方法级别，用于控制方法返回值的缓存行为。 支持设置缓存名称、键值、过期时间等属性。
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisCacheable {
    /**
     * 缓存名称别名
     *
     * <p>与 {@link #cacheNames()} 互为别名，用于指定缓存的名称。
     *
     * @return 缓存名称数组
     */
    @AliasFor("cacheNames")
    String[] value() default {};

    /**
     * 缓存名称
     *
     * <p>与 {@link #value()} 互为别名，用于指定缓存的名称。
     *
     * @return 缓存名称数组
     */
    @AliasFor("value")
    String[] cacheNames() default {};

    /**
     * 缓存键值表达式
     *
     * <p>用于生成缓存的键值，支持SpEL表达式。
     *
     * @return 缓存键值表达式
     */
    String key() default "";

    /**
     * 自定义键生成器
     *
     * <p>指定用于生成缓存键的键生成器bean名称。
     *
     * @return 键生成器bean名称
     */
    String keyGenerator() default "";

    /**
     * 缓存管理器
     *
     * <p>指定用于管理缓存的缓存管理器bean名称。
     *
     * @return 缓存管理器bean名称
     */
    String cacheManager() default "";

    /**
     * 缓存解析器
     *
     * <p>指定用于解析缓存的缓存解析器bean名称。
     *
     * @return 缓存解析器bean名称
     */
    String cacheResolver() default "";

    /**
     * 缓存条件表达式
     *
     * <p>指定只有满足条件时才进行缓存，支持SpEL表达式。
     *
     * @return 条件表达式
     */
    String condition() default "";

    /**
     * 不缓存的条件表达式
     *
     * <p>指定满足条件时则不进行缓存，支持SpEL表达式。
     *
     * @return 条件表达式
     */
    String unless() default "";

    /**
     * 是否同步执行缓存操作
     *
     * <p>true表示同步执行缓存操作，false表示异步执行。
     *
     * @return true表示同步，false表示异步
     */
    boolean sync() default false;

    /**
     * 缓存过期时间（秒）
     *
     * <p>指定缓存的过期时间，单位为秒。
     *
     * @return 过期时间，默认60秒
     */
    long ttl() default 60;

    /**
     * 缓存值的类型
     *
     * <p>指定缓存值的类型，用于类型转换。
     *
     * @return 缓存值的类型，默认为Object.class
     */
    Class<?> type() default Object.class;
}
