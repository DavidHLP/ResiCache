package com.david.spring.cache.redis.annotation;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@CacheEvict
/**
 * Redis缓存驱逐注解类
 * <p>
 * 这是对Spring @CacheEvict注解的扩展，用于Redis缓存的驱逐操作。
 * 支持指定缓存名称、键、条件等参数，提供更灵活的缓存管理能力。
 * </p>
 *
 * <pre>
 * 示例使用：
 * {@code
 * @RedisCacheEvict(value = "myCache", key = "#id")
 * public void deleteById(Long id) {
 *     // 删除逻辑
 * }
 * }
 * </pre>
 *
 * @author David
 * @version 1.0.0
 * @since 2025-09-15
 * @see org.springframework.cache.annotation.CacheEvict
 */
public @interface RedisCacheEvict {

    /**
     * 指定要驱逐的缓存名称数组
     * @return 缓存名称数组，默认空数组
     */
    @AliasFor(annotation = CacheEvict.class, attribute = "value")
    String[] value() default {};

    /**
     * 指定要驱逐的缓存名称数组（与value相同）
     * @return 缓存名称数组，默认空数组
     */
    @AliasFor(annotation = CacheEvict.class, attribute = "value")
    String[] cacheNames() default {};

    /**
     * 指定缓存键表达式
     * @return 缓存键，默认空字符串
     */
    @AliasFor(annotation = CacheEvict.class)
    String key() default "";

    /**
     * 指定键生成器Bean名称
     * @return 键生成器名称，默认空字符串
     */
    @AliasFor(annotation = CacheEvict.class)
    String keyGenerator() default "";

    /**
     * 指定缓存管理器Bean名称
     * @return 缓存管理器名称，默认空字符串
     */
    @AliasFor(annotation = CacheEvict.class)
    String cacheManager() default "";

    /**
     * 指定缓存解析器Bean名称
     * @return 缓存解析器名称，默认空字符串
     */
    @AliasFor(annotation = CacheEvict.class)
    String cacheResolver() default "";

    /**
     * 指定驱逐条件表达式，只有条件为true时才执行驱逐
     * @return 条件表达式，默认空字符串
     */
    @AliasFor(annotation = CacheEvict.class)
    String condition() default "";

    /**
     * 是否驱逐缓存中的所有条目
     * @return true表示驱逐所有条目，false表示按键驱逐，默认false
     */
    @AliasFor(annotation = CacheEvict.class)
    boolean allEntries() default false;

    /**
     * 是否在方法调用前执行驱逐操作
     * @return true表示在调用前驱逐，false表示在调用后驱逐，默认false
     */
    @AliasFor(annotation = CacheEvict.class)
    boolean beforeInvocation() default false;

    /**
     * 是否同步执行缓存驱逐操作（Redis特有）
     * @return true表示同步驱逐，false表示异步驱逐，默认false
     */
    boolean sync() default false;
}
