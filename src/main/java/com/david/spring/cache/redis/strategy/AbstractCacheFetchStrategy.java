package com.david.spring.cache.redis.strategy;

import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache.ValueWrapper;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 缓存获取策略抽象基类
 * 提供通用的策略实现基础
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractCacheFetchStrategy implements CacheFetchStrategy {

    protected final CacheInvocationRegistry registry;
    protected final Executor executor;
    protected final CacheOperationService cacheOperationService;

    /**
     * 获取缓存TTL
     */
    protected long getCacheTtl(CacheFetchContext context) {
        return cacheOperationService.getCacheTtl(context.cacheKey(), context.redisTemplate());
    }

    /**
     * 获取本地锁
     */
    protected ReentrantLock obtainLocalLock(CacheFetchContext context) {
        return registry.obtainLock(context.cacheName(), context.key());
    }

    /**
     * 记录调试日志
     */
    protected void logDebug(String message, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(message, args);
        }
    }

    /**
     * 判断是否需要预刷新
     */
    protected boolean shouldPreRefresh(long ttl, long configuredTtl) {
        return cacheOperationService.shouldPreRefresh(ttl, configuredTtl);
    }

    /**
     * 判断是否需要预刷新（支持自定义阈值）
     */
    protected boolean shouldPreRefresh(long ttl, long configuredTtl, double threshold) {
        return cacheOperationService.shouldPreRefresh(ttl, configuredTtl, threshold);
    }

    /**
     * 执行缓存刷新
     */
    protected void doRefresh(CacheFetchContext context, long ttl) {
        CacheOperationService.CacheRefreshCallback callback = new CacheOperationService.CacheRefreshCallback() {
            @Override
            public void putCache(Object key, Object value) {
                context.callback().refresh(context.invocation(), key, context.cacheKey(), ttl);
            }

            @Override
            public String getCacheName() {
                return context.cacheName();
            }
        };

        cacheOperationService.doRefresh(context.invocation(), context.key(),
                                       context.cacheKey(), ttl, callback);
    }
}