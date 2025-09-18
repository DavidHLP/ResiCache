package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 缓存获取策略接口
 * 定义不同的缓存获取行为
 */
public interface CacheFetchStrategy {

    /**
     * 执行缓存获取策略
     *
     * @param context 策略上下文
     * @return 缓存值包装器，如果缓存不存在则返回null
     */
    ValueWrapper fetch(CacheFetchContext context);

    /**
     * 判断是否支持该策略
     *
     * @param invocationContext 调用上下文
     * @return true表示支持，false表示不支持
     */
    boolean supports(CachedInvocationContext invocationContext);

    /**
     * 获取策略优先级，数字越小优先级越高
     *
     * @return 优先级值
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 策略执行上下文
     */
    record CacheFetchContext(
            String cacheName,
            Object key,
            String cacheKey,
            ValueWrapper valueWrapper,
            CachedInvocation invocation,
            CachedInvocationContext invocationContext,
            RedisTemplate<String, Object> redisTemplate,
            CacheFetchCallback callback
    ) {
    }

    /**
     * 缓存获取回调接口
     */
    interface CacheFetchCallback {
        /**
         * 获取基础缓存值
         */
        ValueWrapper getBaseValue(Object key);

        /**
         * 刷新缓存
         */
        void refresh(CachedInvocation invocation, Object key, String cacheKey, long ttl);

        /**
         * 解析配置的TTL时间
         */
        long resolveConfiguredTtlSeconds(Object value, Object key);

        /**
         * 判断是否需要预刷新
         */
        boolean shouldPreRefresh(long ttl, long configuredTtl);
    }
}