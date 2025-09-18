package com.david.spring.cache.redis.reflect.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;

@Slf4j
public final class ContextBeanSupport {

    /**
     * 懒加载解析KeyGenerator Bean，优先按名称解析，失败则按类型解析 仅在需要时解析，解析结果会缓存在内存中
     *
     * @return KeyGenerator实例，如果解析失败返回null
     */
    public static KeyGenerator resolveKeyGenerator(KeyGenerator resolvedKeyGenerator, String name) {
        if (resolvedKeyGenerator != null) {
            log.debug("Using cached KeyGenerator instance");
            return resolvedKeyGenerator;
        }

        log.debug("Resolving KeyGenerator with name: {}", name);
        if (name != null && !name.isBlank()) {
            KeyGenerator bean = SpringContextHolder.getBean(name, KeyGenerator.class);
            if (bean != null) {
                log.debug("Successfully resolved KeyGenerator by name: {}", name);
                return (resolvedKeyGenerator = bean);
            } else {
                log.debug("KeyGenerator not found by name: {}, trying by type", name);
            }
        }

        KeyGenerator byType = SpringContextHolder.getBean(KeyGenerator.class);
        if (byType != null) {
            log.debug("Successfully resolved KeyGenerator by type");
            resolvedKeyGenerator = byType;
        } else {
            log.warn("No KeyGenerator found by name or type");
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
        if (resolvedCacheResolver != null) {
            log.debug("Using cached CacheResolver instance");
            return resolvedCacheResolver;
        }

        log.debug("Resolving CacheResolver with name: {}", name);
        if (name != null && !name.isBlank()) {
            CacheResolver bean = SpringContextHolder.getBean(name, CacheResolver.class);
            if (bean != null) {
                log.debug("Successfully resolved CacheResolver by name: {}", name);
                return (resolvedCacheResolver = bean);
            } else {
                log.debug("CacheResolver not found by name: {}, trying by type", name);
            }
        }

        CacheResolver byType = SpringContextHolder.getBean(CacheResolver.class);
        if (byType != null) {
            log.debug("Successfully resolved CacheResolver by type");
            resolvedCacheResolver = byType;
        } else {
            log.warn("No CacheResolver found by name or type");
        }
        return resolvedCacheResolver;
    }
}
