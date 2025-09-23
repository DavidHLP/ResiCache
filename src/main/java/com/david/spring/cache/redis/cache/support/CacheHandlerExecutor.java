package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.chain.CacheHandlerChain;
import com.david.spring.cache.redis.chain.CacheHandlerChainBuilder;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 缓存处理器执行器。
 * <p>
 * 负责创建和执行缓存处理器链，替代原来的CacheStrategyExecutor。
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheHandlerExecutor {

    private final CacheHandlerChainBuilder chainBuilder;

    /**
     * 创建缓存获取回调。
     *
     * @param cacheName 缓存名称
     * @param cache 缓存实例
     * @param operationService 缓存操作服务
     * @return 缓存获取回调
     */
    public CacheHandlerContext.CacheFetchCallback createFetchCallback(String cacheName,
                                                                       Cache cache,
                                                                       CacheOperationService operationService) {
        return new CacheHandlerContext.CacheFetchCallback() {
            @Override
            public Cache.ValueWrapper getBaseValue(@Nonnull Object key) {
                return cache.get(key);
            }

            @Override
            public void refresh(@Nonnull CachedInvocation invocation, @Nonnull Object key,
                               @Nonnull String cacheKey, long ttl) {
                try {
                    CacheOperationService.CacheRefreshCallback refreshCallback =
                            new CacheOperationService.CacheRefreshCallback() {
                                @Override
                                public void putCache(Object key, Object value) {
                                    cache.put(key, value);
                                }

                                @Override
                                public String getCacheName() {
                                    return cacheName;
                                }
                            };
                    operationService.doRefresh(invocation, key, cacheKey, ttl, refreshCallback);
                } catch (Exception e) {
                    log.error("Cache refresh failed for cache: {}, key: {}, error: {}",
                            cacheName, key, e.getMessage(), e);
                }
            }

            @Override
            public long resolveConfiguredTtlSeconds(Object value, @Nonnull Object key) {
                try {
                    return operationService.resolveConfiguredTtlSeconds(value, key, null);
                } catch (Exception e) {
                    log.warn("Failed to resolve TTL for cache: {}, key: {}, using default", cacheName, key);
                    return -1L;
                }
            }

            @Override
            public boolean shouldPreRefresh(long ttl, long configuredTtl) {
                try {
                    return operationService.shouldPreRefresh(ttl, configuredTtl);
                } catch (Exception e) {
                    log.debug("Pre-refresh check failed for cache: {}, defaulting to false", cacheName);
                    return false;
                }
            }

            @Override
            public void evictCache(@Nonnull String cacheName, @Nonnull Object key) {
                try {
                    cache.evict(key);
                    log.debug("Cache key evicted: cache={}, key={}", cacheName, key);
                } catch (Exception e) {
                    log.error("Failed to evict cache key: cache={}, key={}, error={}",
                            cacheName, key, e.getMessage(), e);
                    throw e;
                }
            }

            @Override
            public void clearCache(@Nonnull String cacheName) {
                try {
                    cache.clear();
                    log.debug("Cache cleared: cache={}", cacheName);
                } catch (Exception e) {
                    log.error("Failed to clear cache: cache={}, error={}", cacheName, e.getMessage(), e);
                    throw e;
                }
            }

            @Override
            public void cleanupRegistries(@Nonnull String cacheName, @Nonnull Object key) {
                // 注册表清理操作需要通过CacheOperationService执行
                // 这里暂时留空，具体实现在处理器中直接调用注册表
                log.debug("Registry cleanup requested for: cache={}, key={}", cacheName, key);
            }

            @Override
            public void cleanupAllRegistries(@Nonnull String cacheName) {
                // 注册表清理操作需要通过CacheOperationService执行
                // 这里暂时留空，具体实现在处理器中直接调用注册表
                log.debug("All registries cleanup requested for: cache={}", cacheName);
            }
        };
    }

    /**
     * 创建处理器上下文。
     *
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @param cacheKey Redis缓存键
     * @param valueWrapper 缓存值包装器
     * @param invocation 缓存调用信息
     * @param redisTemplate Redis模板
     * @param callback 回调接口
     * @return 处理器上下文
     */
    public CacheHandlerContext createHandlerContext(String cacheName,
                                                    Object key,
                                                    String cacheKey,
                                                    Cache.ValueWrapper valueWrapper,
                                                    CachedInvocation invocation,
                                                    RedisTemplate<String, Object> redisTemplate,
                                                    CacheHandlerContext.CacheFetchCallback callback) {
        return createHandlerContext(cacheName, key, cacheKey, valueWrapper,
                invocation, redisTemplate, callback, com.david.spring.cache.redis.chain.CacheOperationType.READ);
    }

    /**
     * 创建处理器上下文（带操作类型）。
     *
     * @param cacheName 缓存名称
     * @param key 缓存键
     * @param cacheKey Redis缓存键
     * @param valueWrapper 缓存值包装器
     * @param invocation 缓存调用信息
     * @param redisTemplate Redis模板
     * @param callback 回调接口
     * @param operationType 操作类型
     * @return 处理器上下文
     */
    public CacheHandlerContext createHandlerContext(String cacheName,
                                                    Object key,
                                                    String cacheKey,
                                                    Cache.ValueWrapper valueWrapper,
                                                    CachedInvocation invocation,
                                                    RedisTemplate<String, Object> redisTemplate,
                                                    CacheHandlerContext.CacheFetchCallback callback,
                                                    com.david.spring.cache.redis.chain.CacheOperationType operationType) {
        return new CacheHandlerContext(
                cacheName,
                key,
                cacheKey,
                valueWrapper,
                null, // 初始时没有处理结果
                invocation,
                invocation.getCachedInvocationContext(),
                redisTemplate,
                callback,
                operationType
        );
    }

    /**
     * 执行处理器链并处理异常。
     *
     * @param context 处理器上下文
     * @param fallbackValue 回退值
     * @param key 缓存键
     * @param cacheName 缓存名称
     * @return 处理结果
     */
    public Cache.ValueWrapper executeHandlersWithFallback(CacheHandlerContext context,
                                                          Cache.ValueWrapper fallbackValue,
                                                          Object key,
                                                          String cacheName) {
        long startTime = System.currentTimeMillis();
        String operationId = generateOperationId(key);

        try {
            log.debug("[{}] Starting handler chain execution: cache={}, key={}", operationId, cacheName, key);

            // 构建处理器链
            CacheHandlerChain chain = chainBuilder.buildChain(context.invocationContext());

            if (chain.isEmpty()) {
                log.error("[{}] No handlers available, using fallback: cache={}, key={}",
                        operationId, cacheName, key);
                return fallbackValue;
            }

            // 执行处理器链
            Cache.ValueWrapper result = chain.execute(context);
            long duration = System.currentTimeMillis() - startTime;

            if (result != null) {
                logSuccessfulExecution(operationId, duration, cacheName, key, result);
                return result;
            } else {
                log.debug("[{}] Handler chain execution returned null in {}ms: cache={}, key={}, processing null result",
                        operationId, duration, cacheName, key);
                return handleNullResult(context, fallbackValue, operationId);
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("[{}] Handler chain execution failed in {}ms for cache: {}, key: {}: {}, using fallback",
                    operationId, duration, cacheName, key, e.getMessage());
            return fallbackValue;
        }
    }

    /**
     * 处理处理器链返回null结果。
     */
    private Cache.ValueWrapper handleNullResult(CacheHandlerContext context,
                                               Cache.ValueWrapper fallbackValue,
                                               String operationId) {
        CachedInvocationContext invocationContext = context.invocationContext();

        // 检查是否允许空值缓存
        if (invocationContext.cacheNullValues()) {
            log.debug("[{}] Null result accepted due to cacheNullValues=true: cache={}, key={}",
                    operationId, context.cacheName(), context.key());
            return null; // 返回null表示空值缓存
        }

        log.debug("[{}] Using fallback value due to null result and cacheNullValues=false: cache={}, key={}",
                operationId, context.cacheName(), context.key());
        return fallbackValue;
    }

    /**
     * 记录成功执行的日志。
     */
    private void logSuccessfulExecution(String operationId, long duration,
                                       String cacheName, Object key,
                                       Cache.ValueWrapper result) {
        if (duration > 100) {
            log.warn("[{}] Slow handler chain execution in {}ms: cache={}, key={}, hasValue={}",
                    operationId, duration, cacheName, key, result.get() != null);
        } else {
            log.debug("[{}] Handler chain execution successful in {}ms: cache={}, key={}, hasValue={}",
                    operationId, duration, cacheName, key, result.get() != null);
        }
    }

    /**
     * 生成操作ID。
     */
    private String generateOperationId(Object key) {
        return String.format("%s-%d", String.valueOf(key).hashCode(), System.currentTimeMillis() % 10000);
    }
}