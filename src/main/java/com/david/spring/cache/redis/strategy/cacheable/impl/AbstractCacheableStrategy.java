package com.david.spring.cache.redis.strategy.cacheable.impl;

import com.david.spring.cache.redis.strategy.cacheable.CacheableStrategy;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import com.david.spring.cache.redis.strategy.cacheable.support.CacheOperationSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * 抽象缓存获取策略，提供带值加载器的默认实现
 *
 * @author David
 */
@RequiredArgsConstructor
public abstract class AbstractCacheableStrategy implements CacheableStrategy<Object> {

    protected final CacheOperationSupport cacheOperationSupport;

    @Override
    @Nullable
    public <V> V get(@NonNull CacheableContext<Object> context, @NonNull Callable<V> valueLoader) {
        return cacheOperationSupport.getWithFallback(context, valueLoader);
    }
}
