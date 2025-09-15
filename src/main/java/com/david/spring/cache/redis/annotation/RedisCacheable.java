package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * 声明基于 Redis 的缓存注解，封装并扩展了 Spring 的 {@link Cacheable} 注解。
 * <p>
 * 用途与功能：
 * <ul>
 *   <li>作为 {@link Cacheable} 的组合注解，提供完全一致的属性（通过 {@link AliasFor} 进行别名映射）。</li>
 *   <li>默认指定 {@code cacheManager} 为 {@code redisProCacheManager}，便于统一接入 Redis 缓存。</li>
 *   <li>在标准属性基础上扩展了 {@link #ttl()}（过期时间，单位秒）与 {@link #type()}（缓存值反序列化目标类型）以便更好地适配 Redis 场景。</li>
 * </ul>
 * 设计说明：
 * <ul>
 *   <li>该注解可用于类或方法级别；当用于类上时，其配置将作为类内方法的默认缓存配置。</li>
 *   <li>所有与 {@link Cacheable} 对应的属性均通过 {@link AliasFor} 进行别名映射，行为与原生注解保持一致。</li>
 *   <li>{@link #ttl()} 与 {@link #type()} 的实际生效取决于项目中的缓存实现（例如自定义的 Redis Cache 实现）。</li>
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Cacheable
public @interface RedisCacheable {

    /**
     * 指定缓存名称，与 {@link Cacheable#value()} 等价。通常与 {@link #cacheNames()} 二选一使用。
     *
     * @return 缓存名称数组。当未指定时返回空数组。
     */
    @AliasFor(annotation = Cacheable.class, attribute = "value")
    String[] value() default {};

    /**
     * 指定缓存名称，与 {@link Cacheable#cacheNames()} 等价。通常与 {@link #value()} 二选一使用。
     *
     * @return 缓存名称数组。当未指定时返回空数组。
     */
    @AliasFor(annotation = Cacheable.class, attribute = "cacheNames")
    String[] cacheNames() default {};

    /**
     * 缓存键生成的 SpEL 表达式，与 {@link Cacheable#key()} 等价。
     * 例如：{@code #root.methodName + ':' + #id}。
     *
     * @return 用于计算缓存键的表达式字符串，未指定时返回空字符串。
     */
    @AliasFor(annotation = Cacheable.class)
    String key() default "";

    /**
     * 指定自定义键生成器 Bean 名称，与 {@link Cacheable#keyGenerator()} 等价。
     * 当同时配置了 {@link #key()} 与 {@link #keyGenerator()} 时，通常 {@link #key()} 优先生效。
     *
     * @return 键生成器 Bean 名称，未指定时返回空字符串。
     */
    @AliasFor(annotation = Cacheable.class)
    String keyGenerator() default "";

    /**
     * 指定使用的缓存管理器 Bean 名称，与 {@link Cacheable#cacheManager()} 等价。
     * 该注解默认设置为 {@code "redisProCacheManager"} 以便统一接入 Redis 缓存实现。
     *
     * @return 缓存管理器 Bean 名称。
     */
    @AliasFor(annotation = Cacheable.class)
    String cacheManager() default "redisProCacheManager";

    /**
     * 指定缓存解析器 Bean 名称，与 {@link Cacheable#cacheResolver()} 等价。
     * 通常无需与 {@link #cacheManager()} 同时配置。
     *
     * @return 缓存解析器 Bean 名称，未指定时返回空字符串。
     */
    @AliasFor(annotation = Cacheable.class)
    String cacheResolver() default "";

    /**
     * 条件缓存的 SpEL 表达式，与 {@link Cacheable#condition()} 等价。
     * 返回 true 时才会进行缓存。
     *
     * @return 条件表达式字符串，未指定时返回空字符串。
     */
    @AliasFor(annotation = Cacheable.class)
    String condition() default "";

    /**
     * 缓存排除条件的 SpEL 表达式，与 {@link Cacheable#unless()} 等价。
     * 返回 true 时跳过缓存（优先级通常高于 {@link #condition()}）。
     *
     * @return 排除缓存的表达式字符串，未指定时返回空字符串。
     */
    @AliasFor(annotation = Cacheable.class)
    String unless() default "";

    /**
     * 是否使用同步缓存，与 {@link Cacheable#sync()} 等价。
     * 为 true 时在并发场景对同一 key 的加载进行同步以避免缓存击穿。
     *
     * @return 当需要同步加载时返回 true，否则返回 false。
     */
    @AliasFor(annotation = Cacheable.class)
    boolean sync() default false;

    /**
     * 缓存条目的过期时间（单位：秒）。该属性为扩展属性，实际生效取决于具体的缓存实现。
     *
     * @return 过期时间秒数，默认 60 秒。
     */
    long ttl() default 60;

    /**
     * 指定缓存值反序列化的目标类型。该属性为扩展属性，常用于自定义 Redis 序列化配置，
     * 以便在读取缓存时将数据转换为指定的类型。
     *
     * @return 目标类型的 {@link Class} 对象，默认 {@link Object}。
     */
    Class<?> type() default Object.class;
}

