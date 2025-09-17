package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategy;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheGetContext;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * 抽象缓存获取策略，提供带值加载器的默认实现
 *
 * @author David
 */
@Slf4j
public abstract class AbstractCacheableStrategy implements CacheableStrategy<Object> {

    @Override
    @Nullable
    public <V> V get(@NonNull CacheGetContext<Object> context, @NonNull Callable<V> valueLoader) {
        // 首先尝试从缓存获取
        Cache.ValueWrapper wrapper = get(context);
        if (wrapper != null) {
            //noinspection unchecked
            return (V) wrapper.get();
        }

        // 缓存未命中，调用值加载器
        log.debug("Cache miss, invoking value loader for key: {}", context.getKey());
        try {
            V value = valueLoader.call();
            // 将加载的值放入缓存（支持按注解控制缓存null值）
            CachedInvocationContext cic = context.getCachedInvocationContext();
            boolean shouldCache = value != null || (cic != null && cic.cacheNullValues());
            if (shouldCache) {
                context.getParentCache().put(context.getKey(), value);
                log.debug("Value loaded and cached for key: {} (nullCached={})", context.getKey(), value == null);
            }
            return value;
        } catch (Exception e) {
            log.error("Error during basic cache get with value loader for key: {}", context.getKey(), e);
            throw new Cache.ValueRetrievalException(context.getKey(), valueLoader, e);
        }
    }
}
