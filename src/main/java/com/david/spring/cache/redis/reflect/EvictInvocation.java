package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.abstracts.AbstractInvocation;
import com.david.spring.cache.redis.reflect.support.ContextBeanSupport;

import lombok.*;

import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/** 封装一次缓存驱逐（Evict）方法调用的上下文信息。 仅用于记录与注册，实际驱逐由 Spring Cache @CacheEvict 机制与 Cache 实现执行。 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class EvictInvocation extends AbstractInvocation {

    /** 目标 Bean（被 AOP 拦截的对象） */
    private Object targetBean;

    /** 目标方法 */
    private Method targetMethod;

    /** 调用参数 */
    private Object[] arguments;

    /** 缓存驱逐上下文信息 */
    private EvictInvocationContext evictInvocationContext;

    /** 已解析的 KeyGenerator Bean（延迟加载，不参与序列化） */
    private transient KeyGenerator resolvedKeyGenerator;

    /** 已解析的 CacheResolver Bean（延迟加载，不参与序列化） */
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
     * 延迟解析 KeyGenerator Bean：优先按名称解析，失败则按类型解析
     *
     * @return KeyGenerator 实例或 null
     */
    public KeyGenerator resolveKeyGenerator() {
        if (resolvedKeyGenerator != null) return resolvedKeyGenerator;
        KeyGenerator kg =
                ContextBeanSupport.resolveKeyGenerator(
                        null, evictInvocationContext == null ? null : evictInvocationContext.keyGenerator());
        this.resolvedKeyGenerator = kg;
        return kg;
    }

    /**
     * 延迟解析 CacheResolver Bean：优先按名称解析，失败则按类型解析
     *
     * @return CacheResolver 实例或 null
     */
    public CacheResolver resolveCacheResolver() {
        if (resolvedCacheResolver != null) return resolvedCacheResolver;
        CacheResolver cr =
                ContextBeanSupport.resolveCacheResolver(
                        null, evictInvocationContext == null ? null : evictInvocationContext.cacheResolver());
        this.resolvedCacheResolver = cr;
        return cr;
    }

    /** 清除已解析的 Bean 缓存，下次调用将重新解析。 */
    public void clearResolved() {
        this.resolvedKeyGenerator = null;
        this.resolvedCacheResolver = null;
    }

    /** 缓存驱逐上下文信息记录类 */
    @Builder
    public record EvictInvocationContext(
            /* 缓存名称别名 */
            String[] value,
            /* 缓存名称 */
            String[] cacheNames,
            /* 缓存键表达式 */
            String key,
            /* KeyGenerator Bean 名称 */
            String keyGenerator,
            /* CacheManager Bean 名称 */
            String cacheManager,
            /* CacheResolver Bean 名称 */
            String cacheResolver,
            /* 条件表达式 */
            String condition,
            /* 是否清除所有条目 */
            boolean allEntries,
            /* 是否在方法调用前执行清除 */
            boolean beforeInvocation,
            /* 是否同步执行 */
            boolean sync) {}
}
