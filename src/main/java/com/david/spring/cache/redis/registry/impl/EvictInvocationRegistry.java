package com.david.spring.cache.redis.registry.impl;

import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.registry.AbstractInvocationRegistry;
import com.david.spring.cache.redis.registry.keys.Key;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 驱逐调用注册表，用于注册和管理 (cacheName, key) -> EvictInvocation 的映射
 * 提供细粒度的 Key 级锁，便于在需要时关联驱逐调用上下文与并发控制
 * 支持通配符key（allEntries=true场景）
 * 主人，现在这个驱逐注册表也变得简洁明了了喵~
 */
@Component
public class EvictInvocationRegistry extends AbstractInvocationRegistry<EvictInvocation> {

    @Override
    protected String getInvocationType() {
        return "evict";
    }

    /**
     * 创建Key实例，支持null key（用于allEntries=true场景）
     * null key 会被标准化为通配符"*"
     * 
     * @param cacheName 缓存名称
     * @param key       缓存键，可以为null
     * @return 标准化的Key实例
     */
    @Override
    protected Key createKey(String cacheName, @Nullable Object key) {
        return key == null ? Key.wildcard(cacheName) : Key.of(cacheName, key);
    }
}
