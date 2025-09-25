package com.david.spring.cache.redis.core.strategy;

import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.core.RedisCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 默认策略实现
 * 直接执行缓存操作，不使用任何锁机制
 */
@Slf4j
@Component
public class DefaultStrategy implements CacheExecutionStrategy {

    private final RedisCacheManager cacheManager;
    private final KeyGenerator keyGenerator;

    public DefaultStrategy(RedisCacheManager cacheManager,
                          @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator) {
        this.cacheManager = cacheManager;
        this.keyGenerator = keyGenerator;
    }

    @Override
    public Object execute(ProceedingJoinPoint joinPoint,
                         List<CacheOperationResolver.CacheableOperation> operations,
                         Method method,
                         Object[] args,
                         Class<?> targetClass) throws Throwable {

        // 先检查缓存
        Object cachedResult = getCachedValue(operations, method, args, joinPoint.getTarget(), targetClass);
        if (cachedResult != null) {
            return cachedResult;
        }

        // 执行方法并缓存结果
        return executeAndCache(joinPoint, operations, method, args, targetClass);
    }

    @Override
    public boolean supports(CacheOperationResolver.CacheableOperation operation) {
        // 默认策略支持所有操作，但优先级最低
        return true;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE; // 最低优先级
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
        return keyGenerator.generate(target, method, args);
    }
}