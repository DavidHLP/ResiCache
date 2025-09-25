package com.david.spring.cache.redis.core.strategy;

import com.david.spring.cache.redis.core.CacheExpressionEvaluator;
import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.core.RedisCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 同步策略实现
 * 使用Java synchronized关键字来保证缓存操作的线程安全
 */
@Slf4j
@Component
public class SyncStrategy implements CacheExecutionStrategy {

    private final RedisCacheManager cacheManager;
    private final KeyGenerator keyGenerator;
    private final CacheExpressionEvaluator expressionEvaluator;

    public SyncStrategy(RedisCacheManager cacheManager,
                       @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator,
                       CacheExpressionEvaluator expressionEvaluator) {
        this.cacheManager = cacheManager;
        this.keyGenerator = keyGenerator;
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public Object execute(ProceedingJoinPoint joinPoint,
                         List<CacheOperationResolver.CacheableOperation> operations,
                         Method method,
                         Object[] args,
                         Class<?> targetClass) throws Throwable {

        CacheOperationResolver.CacheableOperation primaryOperation = operations.get(0);
        Object key = generateCacheKey(primaryOperation, method, args, joinPoint.getTarget(), targetClass);
        String lockKey = "cache:sync:" + primaryOperation.getCacheNames()[0] + ":" + key;

        synchronized (lockKey.intern()) {
            log.debug("Acquired sync lock for key: {}", lockKey);

            // 获取锁后再次检查缓存
            Object cachedResult = getCachedValue(operations, method, args, joinPoint.getTarget(), targetClass);
            if (cachedResult != null) {
                return cachedResult;
            }

            // 执行方法并缓存结果
            return executeAndCache(joinPoint, operations, method, args, targetClass);
        }
    }

    @Override
    public boolean supports(CacheOperationResolver.CacheableOperation operation) {
        return operation.isSync() || operation.isInternalLock();
    }

    @Override
    public int getOrder() {
        return 2; // 次高优先级
    }

    private Object getCachedValue(List<CacheOperationResolver.CacheableOperation> operations,
                                 Method method, Object[] args, Object target, Class<?> targetClass) {
        for (CacheOperationResolver.CacheableOperation operation : operations) {
            for (String cacheName : operation.getCacheNames()) {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    Object key = generateCacheKey(operation, method, args, target, targetClass);
                    Cache.ValueWrapper wrapper = cache.get(key);
                    if (wrapper != null) {
                        log.debug("Cache hit for key: {}", key);
                        return wrapper.get();
                    }
                }
            }
        }
        return null;
    }

    private Object executeAndCache(ProceedingJoinPoint joinPoint,
                                  List<CacheOperationResolver.CacheableOperation> operations,
                                  Method method, Object[] args, Class<?> targetClass) throws Throwable {
        Object result = joinPoint.proceed();

        // 缓存结果
        for (CacheOperationResolver.CacheableOperation operation : operations) {
            for (String cacheName : operation.getCacheNames()) {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    Object key = generateCacheKey(operation, method, args, joinPoint.getTarget(), targetClass);
                    cache.put(key, result);
                    log.debug("Cached result for key: {}", key);
                }
            }
        }

        return result;
    }

    private Object generateCacheKey(CacheOperationResolver.CacheableOperation operation,
                                   Method method, Object[] args, Object target, Class<?> targetClass) {
        // 如果指定了key表达式，使用表达式计算键
        if (StringUtils.hasText(operation.getKey())) {
            Object key = expressionEvaluator.generateKey(operation.getKey(), method, args, target, targetClass, null);
            return key != null ? key : keyGenerator.generate(target, method, args);
        }

        // 否则使用默认的KeyGenerator
        return keyGenerator.generate(target, method, args);
    }
}