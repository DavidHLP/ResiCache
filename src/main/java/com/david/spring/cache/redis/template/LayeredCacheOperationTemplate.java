package com.david.spring.cache.redis.template;

import com.david.spring.cache.redis.core.CacheExpressionEvaluator;
import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.core.LayeredCache;
import com.david.spring.cache.redis.core.RedisCacheManager;
import com.david.spring.cache.redis.event.CacheEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 分层缓存操作模板实现
 * 专门处理分层缓存的操作逻辑
 */
@Slf4j
public class LayeredCacheOperationTemplate extends CacheOperationTemplate {

    private final RedisCacheManager cacheManager;
    private final KeyGenerator keyGenerator;
    private final CacheExpressionEvaluator expressionEvaluator;

    public LayeredCacheOperationTemplate(CacheEventPublisher eventPublisher,
                                       RedisCacheManager cacheManager,
                                       @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator) {
        super(eventPublisher);
        this.cacheManager = cacheManager;
        this.keyGenerator = keyGenerator;
        this.expressionEvaluator = new CacheExpressionEvaluator();
    }

    @Override
    protected Object generateCacheKey(CacheOperationResolver.CacheableOperation operation,
                                    Method method, Object[] args, Object target, Class<?> targetClass) {
        if (operation.hasKey()) {
            Object key = expressionEvaluator.generateKey(operation.getKey(), method, args, target, targetClass, null);
            return key != null ? key : keyGenerator.generate(target, method, args);
        }
        return keyGenerator.generate(target, method, args);
    }

    @Override
    protected boolean shouldExecute(CacheOperationResolver.CacheableOperation operation,
                                  Method method, Object[] args, Object target, Class<?> targetClass, Object result) {
        return !operation.hasCondition() ||
                expressionEvaluator.evaluateCondition(operation.getCondition(), method, args, target, targetClass, result);
    }

    @Override
    protected Object getCachedValue(CacheOperationResolver.CacheableOperation operation,
                                  Object cacheKey, String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return null;
        }

        // 对于分层缓存，先检查本地缓存再检查远程缓存
        if (cache instanceof LayeredCache layeredCache) {
            // 先查本地缓存
            Cache.ValueWrapper localValue = layeredCache.getLocalCache().get(cacheKey);
            if (localValue != null) {
                log.debug("Local cache hit for key: {}", cacheKey);
                return localValue.get();
            }

            // 查远程缓存
            Cache.ValueWrapper remoteValue = layeredCache.getRemoteCache().get(cacheKey);
            if (remoteValue != null) {
                log.debug("Remote cache hit for key: {}, syncing to local cache", cacheKey);
                // 同步到本地缓存
                layeredCache.getLocalCache().put(cacheKey, remoteValue.get());
                return remoteValue.get();
            }

            return null;
        } else {
            Cache.ValueWrapper wrapper = cache.get(cacheKey);
            return wrapper != null ? wrapper.get() : null;
        }
    }

    @Override
    protected Object executeTargetMethod(ProceedingJoinPoint joinPoint,
                                       CacheOperationResolver.CacheableOperation operation,
                                       Object cacheKey, String cacheName) throws Throwable {
        long startTime = System.currentTimeMillis();

        // 执行目标方法
        Object result = joinPoint.proceed();

        long executionTime = System.currentTimeMillis() - startTime;

        // 缓存结果
        if (result != null || operation.isCacheNullValues()) {
            Duration ttl = operation.getTtl();
            cacheResult(operation, cacheKey, cacheName, result, ttl);

            String source = joinPoint.getTarget().getClass().getSimpleName() + "." +
                           joinPoint.getSignature().getName();
            onCachePut(operation, cacheKey, cacheName, result, source, ttl, executionTime);
        }

        return result;
    }

    @Override
    protected void cacheResult(CacheOperationResolver.CacheableOperation operation,
                             Object cacheKey, String cacheName, Object result, Duration ttl) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }

        // 分层缓存会同时更新本地和远程缓存
        cache.put(cacheKey, result);
        log.debug("Cached result to layered cache for key: {} with TTL: {}", cacheKey, ttl);
    }

    @Override
    protected void onCacheHit(CacheOperationResolver.CacheableOperation operation,
                            Object cacheKey, String cacheName, Object value,
                            String source, long startTime) {
        super.onCacheHit(operation, cacheKey, cacheName, value, source, startTime);

        // 对于分层缓存，可以记录是本地命中还是远程命中
        Cache cache = cacheManager.getCache(cacheName);
        if (cache instanceof LayeredCache layeredCache) {
            Cache.ValueWrapper localValue = layeredCache.getLocalCache().get(cacheKey);
            if (localValue != null) {
                log.debug("Layered cache local hit for key: {}", cacheKey);
            } else {
                log.debug("Layered cache remote hit for key: {}", cacheKey);
            }
        }
    }
}