package io.github.davidhlp.spring.cache.redis.core.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import io.github.davidhlp.spring.cache.redis.core.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.core.factory.EvictOperationFactory;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Component
public class CachingAnnotationHandler extends AnnotationHandler {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator;
    private final CacheableOperationFactory cacheableOperationFactory;
    private final EvictOperationFactory evictOperationFactory;

    public CachingAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CacheableOperationFactory cacheableOperationFactory,
            EvictOperationFactory evictOperationFactory) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
        this.cacheableOperationFactory = cacheableOperationFactory;
        this.evictOperationFactory = evictOperationFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return method.isAnnotationPresent(RedisCaching.class);
    }

    @Override
    protected void doHandle(Method method, Object target, Object[] args) {
        RedisCaching caching = method.getAnnotation(RedisCaching.class);

        // 处理组合注解中的 @RedisCacheable
        for (RedisCacheable cacheable : caching.redisCacheable()) {
            registerCacheableOperation(method, target, args, cacheable);
        }

        // 处理组合注解中的 @RedisCacheEvict
        for (RedisCacheEvict evict : caching.redisCacheEvict()) {
            registerCacheEvictOperation(method, target, args, evict);
        }
    }

    private void registerCacheableOperation(
            Method method, Object target, Object[] args, RedisCacheable redisCacheable) {
        try {
            String key = generateKey(target, method, args);
            RedisCacheableOperation operation =
                    cacheableOperationFactory.create(method, redisCacheable, target, args, key);

            redisCacheRegister.registerCacheableOperation(operation);
            log.debug(
                    "Registered cacheable operation from @RedisCaching: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
        } catch (Exception e) {
            log.error("Failed to register cacheable operation from @RedisCaching", e);
        }
    }

    private void registerCacheEvictOperation(
            Method method, Object target, Object[] args, RedisCacheEvict cacheEvict) {
        try {
            String key = generateKey(target, method, args);
            RedisCacheEvictOperation operation =
                    evictOperationFactory.create(method, cacheEvict, target, args, key);

            redisCacheRegister.registerCacheEvictOperation(operation);
            log.debug(
                    "Registered cache evict operation from @RedisCaching: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
        } catch (Exception e) {
            log.error("Failed to register cache evict operation from @RedisCaching", e);
        }
    }

    private String generateKey(Object target, Method method, Object[] args) {
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }
}
