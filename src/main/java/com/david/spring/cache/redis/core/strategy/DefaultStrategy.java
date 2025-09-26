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
 * 默认策略实现
 * 直接执行缓存操作，不使用任何锁机制
 */
@Slf4j
@Component
public class DefaultStrategy extends AbstractCacheExecutionStrategy {

    public DefaultStrategy(RedisCacheManager cacheManager,
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

        Object cachedResult = getCachedValue(operations, method, args, joinPoint.getTarget(), targetClass);
        if (cachedResult != null) {
            return cachedResult;
        }

        return executeAndCache(joinPoint, operations, method, args, targetClass);
    }

    @Override
    public boolean supports(CacheOperationResolver.CacheableOperation operation) {
        return true;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}