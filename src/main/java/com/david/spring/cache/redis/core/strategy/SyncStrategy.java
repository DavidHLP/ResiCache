package com.david.spring.cache.redis.core.strategy;

import com.david.spring.cache.redis.core.CacheExpressionEvaluator;
import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.core.RedisCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 同步策略实现
 * 使用Java synchronized关键字来保证缓存操作的线程安全
 */
@Slf4j
@Component
public class SyncStrategy extends AbstractCacheExecutionStrategy {

    public SyncStrategy(RedisCacheManager cacheManager,
                       @Qualifier("redisCacheKeyGenerator") KeyGenerator keyGenerator,
                       CacheExpressionEvaluator expressionEvaluator) {
        super(cacheManager, keyGenerator, expressionEvaluator);
    }

    @Override
    public Object execute(ProceedingJoinPoint joinPoint,
                         List<CacheOperationResolver.CacheableOperation> operations,
                         Method method,
                         Object[] args,
                         Class<?> targetClass) throws Throwable {

        CacheOperationResolver.CacheableOperation primaryOperation = operations.get(0);
        String lockKey = generateLockKey("cache:sync:", primaryOperation, method, args, joinPoint.getTarget(), targetClass);

        synchronized (lockKey.intern()) {
            log.debug("Acquired sync lock for key: {}", lockKey);

            Object cachedResult = getCachedValue(operations, method, args, joinPoint.getTarget(), targetClass);
            if (cachedResult != null) {
                return cachedResult;
            }

            return executeAndCache(joinPoint, operations, method, args, targetClass);
        }
    }

    @Override
    public boolean supports(CacheOperationResolver.CacheableOperation operation) {
        return operation.isSync() || operation.isInternalLock();
    }

    @Override
    public int getOrder() {
        return 2;
    }
}