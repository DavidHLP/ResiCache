package com.david.spring.cache.redis.reflect.support;

import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;

public final class ContextBeanSupport {

    /**
     * 懒加载解析KeyGenerator Bean，优先按名称解析，失败则按类型解析 仅在需要时解析，解析结果会缓存在内存中
     *
     * @return KeyGenerator实例，如果解析失败返回null
     */
    public static KeyGenerator resolveKeyGenerator(KeyGenerator resolvedKeyGenerator, String name) {
        if (resolvedKeyGenerator != null) return resolvedKeyGenerator;
        if (name != null && !name.isBlank()) {
            KeyGenerator bean = SpringContextHolder.getBean(name, KeyGenerator.class);
            if (bean != null) {
                return (resolvedKeyGenerator = bean);
            }
        }
        // 如果按名称解析失败，回退到按类型解析主/默认KeyGenerator
        KeyGenerator byType = SpringContextHolder.getBean(KeyGenerator.class);
        if (byType != null) {
            resolvedKeyGenerator = byType;
        }
        return resolvedKeyGenerator;
    }

    /**
     * 懒加载解析CacheResolver Bean，优先按名称解析，失败则按类型解析 仅在需要时解析，解析结果会缓存在内存中
     *
     * @return CacheResolver实例，如果解析失败返回null
     */
    public static CacheResolver resolveCacheResolver(
            CacheResolver resolvedCacheResolver, String name) {
        if (resolvedCacheResolver != null) return resolvedCacheResolver;
        if (name != null && !name.isBlank()) {
            CacheResolver bean = SpringContextHolder.getBean(name, CacheResolver.class);
            if (bean != null) {
                return (resolvedCacheResolver = bean);
            }
        }
        // 如果按名称解析失败，回退到按类型解析主/默认CacheResolver
        CacheResolver byType = SpringContextHolder.getBean(CacheResolver.class);
        if (byType != null) {
            resolvedCacheResolver = byType;
        }
        return resolvedCacheResolver;
    }
}
