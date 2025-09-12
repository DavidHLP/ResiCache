package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Redis缓存注解，用于标记方法返回值需要被缓存。
 *
 * <p>该注解可以应用于类或方法级别，用于控制方法返回值的缓存行为。 缓存的实现依赖于Spring Cache抽象，同时增加了对Redis特有的过期时间（TTL）支持。
 *
 * <p>使用方式：
 *
 * <pre>{@code
 * @RedisCacheable(value = "userCache", key = "#userId", ttl = 120)
 * public User getUserById(Long userId) { ... }
 * }</pre>
 *
 * <p>注解属性：
 *
 * <ul>
 *   <li>value / cacheNames: 缓存名称，可指定一个或多个缓存。
 *   <li>key: 缓存键表达式，支持SpEL表达式，用于生成缓存的唯一标识。
 *   <li>keyGenerator: 指定自定义缓存键生成器Bean名称，与key互斥。
 *   <li>cacheManager: 指定使用的缓存管理器Bean名称，与cacheResolver互斥。
 *   <li>cacheResolver: 指定自定义缓存解析器Bean名称。
 *   <li>condition: 条件缓存表达式，仅在表达式为true时缓存方法返回值。
 *   <li>unless: 条件不缓存表达式，在表达式为true时不缓存方法返回值（方法执行后生效）。
 *   <li>sync: 是否同步加载缓存，避免多线程重复计算同一个缓存值。
 *   <li>ttl: 缓存过期时间，单位秒，默认60秒，仅在Redis等支持TTL的缓存实现中生效。
 *   <li>type: 缓存值类型，用于类型转换，默认Object.class。
 * </ul>
 *
 * <p>注：此注解本质上是{@link Cacheable}的封装，可通过SpEL表达式或自定义KeyGenerator灵活控制缓存。
 *
 * @author David
 * @since 2025-09-11
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Cacheable
public @interface RedisCacheable {

    /**
     * 缓存名称别名，与 {@link #cacheNames()} 互为别名。
     *
     * @return 缓存名称数组
     */
    @AliasFor(annotation = Cacheable.class, attribute = "value")
    String[] value() default {};

    /**
     * 缓存名称列表，与 {@link #value()} 互为别名。
     *
     * @return 缓存名称数组
     */
    @AliasFor(annotation = Cacheable.class, attribute = "cacheNames")
    String[] cacheNames() default {};

    /**
     * 缓存键表达式，支持SpEL语法，用于生成缓存的唯一标识。
     *
     * @return 缓存键表达式
     */
    @AliasFor(annotation = Cacheable.class)
    String key() default "";

    /**
     * 自定义键生成器Bean名称，与{@link #key()}互斥。
     *
     * @return 键生成器Bean名称
     */
    @AliasFor(annotation = Cacheable.class)
    String keyGenerator() default "";

    /**
     * 缓存管理器Bean名称，与{@link #cacheResolver()}互斥。
     *
     * @return 缓存管理器Bean名称
     */
    @AliasFor(annotation = Cacheable.class)
    String cacheManager() default "";

    /**
     * 缓存解析器Bean名称，用于自定义缓存解析逻辑。
     *
     * @return 缓存解析器Bean名称
     */
    @AliasFor(annotation = Cacheable.class)
    String cacheResolver() default "";

    /**
     * 条件缓存表达式，仅在表达式结果为true时才缓存方法返回值。
     *
     * @return 条件表达式
     */
    @AliasFor(annotation = Cacheable.class)
    String condition() default "";

    /**
     * 条件不缓存表达式，在表达式结果为true时不缓存方法返回值。 与{@link #condition()}不同，此表达式在方法执行后生效，可引用方法返回值。
     *
     * @return 条件表达式
     */
    @AliasFor(annotation = Cacheable.class)
    String unless() default "";

    /**
     * 是否同步加载缓存，避免多线程重复计算相同缓存值。
     *
     * @return true表示同步，false表示异步
     */
    @AliasFor(annotation = Cacheable.class)
    boolean sync() default true;

    /**
     * 缓存过期时间（秒），仅在支持TTL的缓存实现中生效，如Redis。
     *
     * @return 过期时间，默认60秒
     */
    long ttl() default 60;

    /**
     * 缓存值的类型，用于类型转换。
     *
     * @return 缓存值类型，默认为Object.class
     */
    Class<?> type() default Object.class;
}
