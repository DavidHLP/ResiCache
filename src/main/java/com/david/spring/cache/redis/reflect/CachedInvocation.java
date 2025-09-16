package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.abstracts.AbstractInvocation;
import com.david.spring.cache.redis.reflect.support.ContextBeanSupport;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/** 缓存调用封装类，用于包装缓存相关的方法调用信息 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class CachedInvocation extends AbstractInvocation {
    /* 目标Bean实例 */
    private Object targetBean;

    /* 目标方法 */
    private Method targetMethod;

    /* 方法参数数组 */
    private Object[] arguments;

    /* 缓存调用上下文信息 */
    private CachedInvocationContext cachedInvocationContext;

    // 懒加载解析的Bean（不参与序列化）
    private transient KeyGenerator resolvedKeyGenerator;
    private transient CacheResolver resolvedCacheResolver;

    @Override
    protected Object getTargetBean() {
        return targetBean;
    }

    @Override
    protected Method getTargetMethod() {
        return targetMethod;
    }

    @Override
    protected Object[] getArguments() {
        return arguments;
    }

    /**
     * 懒加载解析KeyGenerator Bean，优先按名称解析，失败则按类型解析 仅在需要时解析，解析结果会缓存在内存中
     *
     * @return KeyGenerator实例，如果解析失败返回null
     */
    public KeyGenerator resolveKeyGenerator() {
        if (resolvedKeyGenerator != null) {
            log.debug("Using cached KeyGenerator: {}", resolvedKeyGenerator.getClass().getName());
            return resolvedKeyGenerator;
        }

        final String kgName =
                cachedInvocationContext == null ? null : cachedInvocationContext.keyGenerator();
        log.info("Resolving KeyGenerator from Spring context (name: {})", kgName);

        KeyGenerator kg = ContextBeanSupport.resolveKeyGenerator(null, kgName);
        this.resolvedKeyGenerator = kg;

        if (kg != null) {
            log.info("Resolved KeyGenerator type: {}", kg.getClass().getName());
        } else {
            log.warn("Failed to resolve KeyGenerator (name: {})", kgName);
        }
        return kg;
    }

    /**
     * 懒加载解析CacheResolver Bean，优先按名称解析，失败则按类型解析 仅在需要时解析，解析结果会缓存在内存中
     *
     * @return CacheResolver实例，如果解析失败返回null
     */
    public CacheResolver resolveCacheResolver() {
        if (resolvedCacheResolver != null) {
            log.debug("Using cached CacheResolver: {}", resolvedCacheResolver.getClass().getName());
            return resolvedCacheResolver;
        }

        final String crName =
                cachedInvocationContext == null ? null : cachedInvocationContext.cacheResolver();
        log.info("Resolving CacheResolver from Spring context (name: {})", crName);

        CacheResolver cr = ContextBeanSupport.resolveCacheResolver(null, crName);
        this.resolvedCacheResolver = cr;

        if (cr != null) {
            log.info("Resolved CacheResolver type: {}", cr.getClass().getName());
        } else {
            log.warn("Failed to resolve CacheResolver (name: {})", crName);
        }
        return cr;
    }

    /** 清除已缓存的解析Bean，强制下次调用时重新从上下文解析 */
    public void clearResolved() {
        final boolean hadKg = this.resolvedKeyGenerator != null;
        final boolean hadCr = this.resolvedCacheResolver != null;
        log.info(
                "Clearing resolved beans -> keyGeneratorPresent: {}, cacheResolverPresent: {}",
                hadKg,
                hadCr);
        this.resolvedKeyGenerator = null;
        this.resolvedCacheResolver = null;
    }

    /** 缓存调用上下文记录类 */
    @Builder
    public record CachedInvocationContext(
            /* 缓存名称数组 */
            String[] cacheNames,

            /* 缓存键表达式 */
            String key,

            /* 缓存条件表达式 */
            String condition,

            /* 是否同步执行 */
            boolean sync,

            /* 缓存值表达式 */
            String[] value,

            /* KeyGenerator Bean名称 */
            String keyGenerator,

            /* CacheManager Bean名称 */
            String cacheManager,

            /* CacheResolver Bean名称 */
            String cacheResolver,

            /* 不缓存的条件表达式 */
            String unless,

            /* 缓存过期时间（毫秒） */
            long ttl,

            /* 目标类型 */
            Class<?> type) {}
}
