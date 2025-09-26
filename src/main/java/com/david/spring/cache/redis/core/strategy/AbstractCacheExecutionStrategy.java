package com.david.spring.cache.redis.core.strategy;

import com.david.spring.cache.redis.core.CacheExpressionEvaluator;
import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.core.RedisCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 抽象缓存执行策略基类
 * 提取公共的缓存操作逻辑，减少代码冗余
 */
@Slf4j
public abstract class AbstractCacheExecutionStrategy implements CacheExecutionStrategy {

    protected final RedisCacheManager cacheManager;
    protected final KeyGenerator keyGenerator;
    protected final CacheExpressionEvaluator expressionEvaluator;

    protected AbstractCacheExecutionStrategy(RedisCacheManager cacheManager,
                                           KeyGenerator keyGenerator,
                                           CacheExpressionEvaluator expressionEvaluator) {
        this.cacheManager = cacheManager;
        this.keyGenerator = keyGenerator;
        this.expressionEvaluator = expressionEvaluator;
    }

    /**
     * 获取缓存值
     */
    protected Object getCachedValue(List<CacheOperationResolver.CacheableOperation> operations,
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

    /**
     * 执行方法并缓存结果
     */
    protected Object executeAndCache(ProceedingJoinPoint joinPoint,
                                   List<CacheOperationResolver.CacheableOperation> operations,
                                   Method method, Object[] args, Class<?> targetClass) throws Throwable {
        Object result = joinPoint.proceed();

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

    /**
     * 生成缓存键
     */
    protected Object generateCacheKey(CacheOperationResolver.CacheableOperation operation,
                                    Method method, Object[] args, Object target, Class<?> targetClass) {
        if (StringUtils.hasText(operation.getKey())) {
            Object key = expressionEvaluator.generateKey(operation.getKey(), method, args, target, targetClass, null);
            return key != null ? key : keyGenerator.generate(target, method, args);
        }
        return keyGenerator.generate(target, method, args);
    }

    /**
     * 生成锁键
     */
    protected String generateLockKey(String prefix, CacheOperationResolver.CacheableOperation operation,
                                   Method method, Object[] args, Object target, Class<?> targetClass) {
        Object key = generateCacheKey(operation, method, args, target, targetClass);
        return prefix + operation.getCacheNames()[0] + ":" + key;
    }
}