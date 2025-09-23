package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.chain.CacheHandlerChain;
import com.david.spring.cache.redis.chain.CacheHandlerChainBuilder;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;

@Slf4j
public class HandlerChainExecutor {

    private final CacheHandlerChainBuilder chainBuilder;

    public HandlerChainExecutor(CacheHandlerChainBuilder chainBuilder) {
        this.chainBuilder = chainBuilder;
    }

    public Cache.ValueWrapper execute(CacheHandlerContext context, Cache.ValueWrapper fallbackValue) {
        String operationId = OperationIdGenerator.generate(context.key());
        long startTime = System.currentTimeMillis();

        try {
            log.debug("[{}] Starting handler chain execution: cache={}, key={}",
                    operationId, context.cacheName(), context.key());

            CacheHandlerChain chain = buildChain(context.invocationContext());
            if (chain.isEmpty()) {
                log.error("[{}] No handlers available, using fallback: cache={}, key={}",
                        operationId, context.cacheName(), context.key());
                return fallbackValue;
            }

            Cache.ValueWrapper result = chain.execute(context);
            long duration = System.currentTimeMillis() - startTime;

            return processResult(result, context, fallbackValue, operationId, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("[{}] Handler chain execution failed in {}ms for cache: {}, key: {}: {}, using fallback",
                    operationId, duration, context.cacheName(), context.key(), e.getMessage());
            return fallbackValue;
        }
    }

    private CacheHandlerChain buildChain(CachedInvocationContext invocationContext) {
        return chainBuilder.buildChain(invocationContext);
    }

    private Cache.ValueWrapper processResult(Cache.ValueWrapper result, CacheHandlerContext context,
                                           Cache.ValueWrapper fallbackValue, String operationId, long duration) {
        if (result != null) {
            logSuccessfulExecution(operationId, duration, context, result);
            return result;
        } else {
            log.debug("[{}] Handler chain execution returned null in {}ms: cache={}, key={}, processing null result",
                    operationId, duration, context.cacheName(), context.key());
            return handleNullResult(context, fallbackValue, operationId);
        }
    }

    private Cache.ValueWrapper handleNullResult(CacheHandlerContext context, Cache.ValueWrapper fallbackValue, String operationId) {
        CachedInvocationContext invocationContext = context.invocationContext();

        if (invocationContext.cacheNullValues()) {
            log.debug("[{}] Null result accepted due to cacheNullValues=true: cache={}, key={}",
                    operationId, context.cacheName(), context.key());
            return null;
        }

        log.debug("[{}] Using fallback value due to null result and cacheNullValues=false: cache={}, key={}",
                operationId, context.cacheName(), context.key());
        return fallbackValue;
    }

    private void logSuccessfulExecution(String operationId, long duration, CacheHandlerContext context, Cache.ValueWrapper result) {
        if (duration > 100) {
            log.warn("[{}] Slow handler chain execution in {}ms: cache={}, key={}, hasValue={}",
                    operationId, duration, context.cacheName(), context.key(), result.get() != null);
        } else {
            log.debug("[{}] Handler chain execution successful in {}ms: cache={}, key={}, hasValue={}",
                    operationId, duration, context.cacheName(), context.key(), result.get() != null);
        }
    }

    private static class OperationIdGenerator {
        public static String generate(Object key) {
            return String.format("%s-%d", String.valueOf(key).hashCode(), System.currentTimeMillis() % 10000);
        }
    }
}