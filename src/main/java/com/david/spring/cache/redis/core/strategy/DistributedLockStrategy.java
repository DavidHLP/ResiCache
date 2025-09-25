package com.david.spring.cache.redis.core.strategy;

import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.core.RedisCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁策略实现
 * 使用Redisson分布式锁来保证缓存操作的一致性
 */
@Slf4j
@Component
public class DistributedLockStrategy implements CacheExecutionStrategy {

    private final RedissonClient redissonClient;
    private final RedisCacheManager cacheManager;
    private final KeyGenerator keyGenerator;

    public DistributedLockStrategy(RedissonClient redissonClient,
                                 RedisCacheManager cacheManager,
                                 @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator) {
        this.redissonClient = redissonClient;
        this.cacheManager = cacheManager;
        this.keyGenerator = keyGenerator;
    }

    @Override
    public Object execute(ProceedingJoinPoint joinPoint,
                         List<CacheOperationResolver.CacheableOperation> operations,
                         Method method,
                         Object[] args,
                         Class<?> targetClass) throws Throwable {

        CacheOperationResolver.CacheableOperation primaryOperation = operations.get(0);
        Object key = generateCacheKey(primaryOperation, method, args, joinPoint.getTarget(), targetClass);
        String lockKey = "cache:lock:" + primaryOperation.getCacheNames()[0] + ":" + key;

        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;

        try {
            // 尝试获取分布式锁，最多等待3秒，锁自动过期时间30秒
            acquired = lock.tryLock(3, 30, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Failed to acquire distributed lock for key: {}", lockKey);
                // 获取锁失败，直接执行方法（不缓存结果）
                return joinPoint.proceed();
            }

            log.debug("Acquired distributed lock for key: {}", lockKey);

            // 获取锁后再次检查缓存，可能已经被其他线程缓存了
            Object cachedResult = getCachedValue(operations, method, args, joinPoint.getTarget(), targetClass);
            if (cachedResult != null) {
                return cachedResult;
            }

            // 执行方法并缓存结果
            return executeAndCache(joinPoint, operations, method, args, targetClass);

        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Released distributed lock for key: {}", lockKey);
            }
        }
    }

    @Override
    public boolean supports(CacheOperationResolver.CacheableOperation operation) {
        return operation.isDistributedLock();
    }

    @Override
    public int getOrder() {
        return 1; // 最高优先级
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