package com.david.spring.cache.redis.strategy.cacheable.support;

import com.david.spring.cache.redis.cache.RedisProCache;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * 缓存操作支持类
 * 提供统一的缓存获取、存储和值加载逻辑
 *
 * @author David
 */
@Slf4j
@Component
public class CacheOperationSupport {

    /**
     * 安全获取缓存值，避免循环调用
     *
     * @param context 缓存获取上下文
     * @return 缓存值包装器
     */
    @Nullable
    public Cache.ValueWrapper safeGet(@NonNull CacheableContext<Object> context) {
        try {
            Cache parentCache = context.getParentCache();
            if (parentCache instanceof RedisProCache redisProCache) {
                return redisProCache.getFromParent(context.getKey());
            }
            return parentCache.get(context.getKey());
        } catch (Exception e) {
            log.error("Error during safe cache get for key: {}", context.getKey(), e);
            return null;
        }
    }

    /**
     * 加载值并根据配置决定是否缓存
     *
     * @param context     缓存获取上下文
     * @param valueLoader 值加载器
     * @return 加载的值
     */
    @Nullable
    public <V> V loadAndCache(@NonNull CacheableContext<Object> context, @NonNull Callable<V> valueLoader) {
        try {
            V value = valueLoader.call();

            if (shouldCacheValue(context, value)) {
                context.getParentCache().put(context.getKey(), value);
                log.debug("Value loaded and cached for key: {} (nullCached={})",
                    context.getKey(), value == null);
            } else {
                log.debug("Value loaded but not cached for key: {} (null caching disabled)",
                    context.getKey());
            }

            return value;
        } catch (Exception e) {
            log.error("Error loading value for key: {}", context.getKey(), e);
            throw new Cache.ValueRetrievalException(context.getKey(), valueLoader, e);
        }
    }

    /**
     * 带回退的缓存获取，先尝试从缓存获取，失败则调用值加载器
     *
     * @param context     缓存获取上下文
     * @param valueLoader 值加载器
     * @return 缓存值或加载的值
     */
    @Nullable
    public <V> V getWithFallback(@NonNull CacheableContext<Object> context, @NonNull Callable<V> valueLoader) {
        Cache.ValueWrapper wrapper = safeGet(context);
        if (wrapper != null) {
            @SuppressWarnings("unchecked")
            V value = (V) wrapper.get();
            log.debug("Cache hit for key: {}", context.getKey());
            return value;
        }

        log.debug("Cache miss, loading value for key: {}", context.getKey());
        return loadAndCache(context, valueLoader);
    }

    /**
     * 判断是否应该缓存值（包括null值）
     *
     * @param context 缓存获取上下文
     * @param value   要缓存的值
     * @return 是否应该缓存
     */
    private boolean shouldCacheValue(@NonNull CacheableContext<Object> context, @Nullable Object value) {
        if (value != null) {
            return true;
        }

        CachedInvocationContext cic = context.getCachedInvocationContext();
        return cic != null && cic.cacheNullValues();
    }
}