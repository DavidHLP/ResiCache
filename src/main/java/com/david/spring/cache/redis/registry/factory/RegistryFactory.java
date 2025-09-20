package com.david.spring.cache.redis.registry.factory;

import com.david.spring.cache.redis.registry.AbstractInvocationRegistry;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 注册表工厂类 - 实现开放封闭原则（OCP）
 * 对扩展开放，对修改封闭
 */
@Slf4j
@Component
public class RegistryFactory {

    private final CacheInvocationRegistry cacheInvocationRegistry;
    private final EvictInvocationRegistry evictInvocationRegistry;

    public RegistryFactory(CacheInvocationRegistry cacheInvocationRegistry,
                          EvictInvocationRegistry evictInvocationRegistry) {
        this.cacheInvocationRegistry = cacheInvocationRegistry;
        this.evictInvocationRegistry = evictInvocationRegistry;
    }

    /**
     * 获取缓存调用注册表
     */
    public CacheInvocationRegistry getCacheInvocationRegistry() {
        return cacheInvocationRegistry;
    }

    /**
     * 获取驱逐调用注册表
     */
    public EvictInvocationRegistry getEvictInvocationRegistry() {
        return evictInvocationRegistry;
    }

    /**
     * 根据类型获取注册表
     */
    @SuppressWarnings("unchecked")
    public <T> AbstractInvocationRegistry<T> getRegistry(RegistryType type) {
        return switch (type) {
            case CACHE_INVOCATION -> (AbstractInvocationRegistry<T>) cacheInvocationRegistry;
            case EVICT_INVOCATION -> (AbstractInvocationRegistry<T>) evictInvocationRegistry;
        };
    }

    /**
     * 注册表类型枚举
     */
    public enum RegistryType {
        CACHE_INVOCATION,
        EVICT_INVOCATION
    }

    /**
     * 获取所有注册表统计信息
     */
    public RegistryStats getAllRegistryStats() {
        return new RegistryStats(
            cacheInvocationRegistry.size(),
            evictInvocationRegistry.size()
        );
    }

    /**
     * 注册表统计信息
     */
    public record RegistryStats(
        int cacheInvocations,
        int evictInvocations
    ) {
        public int totalInvocations() {
            return cacheInvocations + evictInvocations;
        }
    }
}