package com.david.spring.cache.redis.core.strategy;

import com.david.spring.cache.redis.core.CacheExpressionEvaluator;
import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.core.RedisCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class DistributedLockStrategy extends AbstractCacheExecutionStrategy {

    private final RedissonClient redissonClient;

    public DistributedLockStrategy(RedissonClient redissonClient,
                                 RedisCacheManager cacheManager,
                                 @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator,
                                 CacheExpressionEvaluator expressionEvaluator) {
        super(cacheManager, keyGenerator, expressionEvaluator);
        this.redissonClient = redissonClient;
    }

    @Override
    public Object execute(ProceedingJoinPoint joinPoint,
                         List<CacheOperationResolver.CacheableOperation> operations,
                         Method method,
                         Object[] args,
                         Class<?> targetClass) throws Throwable {

        CacheOperationResolver.CacheableOperation primaryOperation = operations.get(0);
        String lockKey = generateLockKey("cache:lock:", primaryOperation, method, args, joinPoint.getTarget(), targetClass);

        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;

        try {
            acquired = lock.tryLock(3, 30, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Failed to acquire distributed lock for key: {}", lockKey);
                return joinPoint.proceed();
            }

            log.debug("Acquired distributed lock for key: {}", lockKey);

            Object cachedResult = getCachedValue(operations, method, args, joinPoint.getTarget(), targetClass);
            if (cachedResult != null) {
                return cachedResult;
            }

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
        return 1;
    }
}