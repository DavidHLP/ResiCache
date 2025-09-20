package com.david.spring.cache.redis.registry;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import org.springframework.stereotype.Component;

/**
 * 缓存调用注册表
 * 注册 (cacheName, key) -> CachedInvocation 的映射，并提供细粒度的 Key 级锁
 */
@Component
public class CacheInvocationRegistry extends AbstractInvocationRegistry<CachedInvocation> {

    @Override
    protected boolean isValidForRegistration(String cacheName, Object key, CachedInvocation invocation) {
        // 缓存调用要求 key 不能为空
        return super.isValidForRegistration(cacheName, key, invocation) || key == null;
    }
}
