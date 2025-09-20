package com.david.spring.cache.redis.registry.impl;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.AbstractInvocationRegistry;
import com.david.spring.cache.redis.registry.keys.Key;
import org.springframework.stereotype.Component;

/**
 * 缓存调用注册表，用于注册和管理 (cacheName, key) -> CachedInvocation 的映射
 * 提供细粒度的 Key 级锁，以支撑"即将到期主动刷新缓存"的并发控制
 * 主人，现在这个注册表变得更加简洁和优雅了喵~
 */
@Component
public class CacheInvocationRegistry extends AbstractInvocationRegistry<CachedInvocation> {

    @Override
    protected String getInvocationType() {
        return "cache";
    }

    /**
     * 验证缓存键不能为null（缓存操作需要明确的键）
     * 
     * @param cacheName 缓存名称
     * @param key       缓存键
     * @return 标准化的Key实例
     * @throws IllegalArgumentException 如果key为null
     */
    @Override
    protected Key createKey(String cacheName, Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Cache key cannot be null for cache operations");
        }
        return Key.of(cacheName, key);
    }
}
