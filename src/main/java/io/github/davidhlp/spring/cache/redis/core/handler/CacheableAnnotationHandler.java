package io.github.davidhlp.spring.cache.redis.core.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.core.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Component
public class CacheableAnnotationHandler extends AnnotationHandler {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator;
    private final CacheableOperationFactory cacheableOperationFactory;

    public CacheableAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CacheableOperationFactory cacheableOperationFactory) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
        this.cacheableOperationFactory = cacheableOperationFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return method.isAnnotationPresent(RedisCacheable.class);
    }

    @Override
    protected void doHandle(Method method, Object target, Object[] args) {
        RedisCacheable[] cacheables = method.getAnnotationsByType(RedisCacheable.class);

        for (RedisCacheable cacheable : cacheables) {
            registerCacheableOperation(method, target, args, cacheable);
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
                    "Registered cacheable operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
        } catch (Exception e) {
            log.error("Failed to register cacheable operation", e);
        }
    }

    private String generateKey(Object target, Method method, Object[] args) {
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }
}
