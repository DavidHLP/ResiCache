package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.chain.CacheHandlerChain;
import com.david.spring.cache.redis.chain.CacheHandlerChainBuilder;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;

@Slf4j
public class CacheHandlerService {

    private final CacheHandlerChainBuilder chainBuilder;
    private final CacheContextValidator contextValidator;
    private final CacheHandlerExecutor handlerExecutor;

    public CacheHandlerService(CacheHandlerChainBuilder chainBuilder,
                               CacheContextValidator contextValidator,
                               CacheHandlerExecutor handlerExecutor) {
        this.chainBuilder = chainBuilder;
        this.contextValidator = contextValidator;
        this.handlerExecutor = handlerExecutor;
    }

    public boolean shouldExecuteHandlerChain(CachedInvocation invocation, Cache.ValueWrapper baseValue,
                                             String cacheName, Object key) {
        if (invocation == null || invocation.getCachedInvocationContext() == null) {
            log.debug("No invocation context found for cache: {}, key: {}, returning value as-is", cacheName, key);
            return false;
        }

        CachedInvocationContext invocationContext = invocation.getCachedInvocationContext();
        if (!contextValidator.isValidInvocationContext(invocationContext)) {
            log.warn("Invalid invocation context for cache: {}, key: {}, returning base value", cacheName, key);
            return false;
        }

        if (!contextValidator.shouldExecuteHandlers(invocationContext, baseValue)) {
            log.debug("Skipping handler chain execution for cache: {}, key: {}", cacheName, key);
            return false;
        }

        return true;
    }

    public Cache.ValueWrapper executeHandlerChain(String cacheName, Object key, String cacheKey,
                                                  Cache.ValueWrapper baseValue, CachedInvocation invocation,
                                                  RedisTemplate<String, Object> redisTemplate,
                                                  CacheHandlerContext.CacheFetchCallback callback) {
        CacheHandlerContext context = handlerExecutor.createHandlerContext(
                cacheName, key, cacheKey, baseValue, invocation, redisTemplate, callback);

        if (!contextValidator.isValidHandlerContext(context)) {
            log.warn("Invalid handler context for cache: {}, key: {}, returning base value", cacheName, key);
            return baseValue;
        }

        return handlerExecutor.executeHandlersWithFallback(context, baseValue, key, cacheName);
    }

    public void executeEvictHandlerChain(String cacheName, Object key, String cacheKey,
                                         CachedInvocation invocation,
                                         RedisTemplate<String, Object> redisTemplate,
                                         CacheHandlerContext.CacheFetchCallback callback) {
        CacheHandlerContext context = handlerExecutor.createHandlerContext(
                cacheName, key, cacheKey, null, invocation, redisTemplate, callback,
                com.david.spring.cache.redis.chain.CacheOperationType.EVICT);

        handlerExecutor.executeHandlersWithFallback(context, null, key, cacheName);
    }

    public void executeClearHandlerChain(String cacheName, String cacheKey,
                                         CachedInvocation dummyInvocation,
                                         RedisTemplate<String, Object> redisTemplate,
                                         CacheHandlerContext.CacheFetchCallback callback) {
        CacheHandlerContext context = handlerExecutor.createHandlerContext(
                cacheName, "*", cacheKey, null, dummyInvocation, redisTemplate, callback,
                com.david.spring.cache.redis.chain.CacheOperationType.CLEAR);

        handlerExecutor.executeHandlersWithFallback(context, null, "*", cacheName);
    }

    public CachedInvocation createDummyInvocationForClear() {
        try {
            CachedInvocationContext dummyContext = CachedInvocationContext.builder()
                    .cacheNullValues(false)
                    .enablePreRefresh(false)
                    .preRefreshThreshold(0.3)
                    .build();

            return CachedInvocation.builder()
                    .cachedInvocationContext(dummyContext)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to create dummy invocation for clear operation: {}", e.getMessage());
            return null;
        }
    }
}