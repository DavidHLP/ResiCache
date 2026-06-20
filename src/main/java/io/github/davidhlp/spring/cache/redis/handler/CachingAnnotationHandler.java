package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import io.github.davidhlp.spring.cache.redis.factory.CachePutOperationFactory;
import io.github.davidhlp.spring.cache.redis.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.factory.EvictOperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class CachingAnnotationHandler extends AnnotationHandler {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator;
    private final CacheableOperationFactory cacheableOperationFactory;
    private final EvictOperationFactory evictOperationFactory;
    private final CachePutOperationFactory cachePutOperationFactory;

    public CachingAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CacheableOperationFactory cacheableOperationFactory,
            EvictOperationFactory evictOperationFactory,
            CachePutOperationFactory cachePutOperationFactory) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
        this.cacheableOperationFactory = cacheableOperationFactory;
        this.evictOperationFactory = evictOperationFactory;
        this.cachePutOperationFactory = cachePutOperationFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class) != null;
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCaching caching = AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class);
        List<CacheOperation> operations = new ArrayList<>();

        // 处理组合注解中的 @RedisCacheable
        for (RedisCacheable cacheable : caching.redisCacheable()) {
            RedisCacheableOperation operation = registerCacheableOperation(method, target, args, cacheable);
            if (operation != null) {
                operations.add(operation);
            }
        }

        // 处理组合注解中的 @RedisCacheEvict
        for (RedisCacheEvict evict : caching.redisCacheEvict()) {
            RedisCacheEvictOperation operation = registerCacheEvictOperation(method, target, args, evict);
            if (operation != null) {
                operations.add(operation);
            }
        }

        // 处理组合注解中的 @RedisCachePut
        for (RedisCachePut put : caching.redisCachePut()) {
            RedisCachePutOperation operation = registerCachePutOperation(method, target, args, put);
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }

    private RedisCacheableOperation registerCacheableOperation(
            Method method, Object target, Object[] args, RedisCacheable redisCacheable) {
        try {
            String key = generateKey(target, method, args, redisCacheable.key());
            RedisCacheableOperation operation =
                    cacheableOperationFactory.create(method, redisCacheable, target, args, key);

            Class<?> targetClass = target != null ? target.getClass() : null;
            redisCacheRegister.registerCacheableOperation(method, targetClass, operation);
            log.debug(
                    "Registered cacheable operation from @RedisCaching: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register cacheable operation from @RedisCaching", e);
            return null;
        }
    }

    private RedisCacheEvictOperation registerCacheEvictOperation(
            Method method, Object target, Object[] args, RedisCacheEvict cacheEvict) {
        try {
            String key = generateKey(target, method, args, cacheEvict.key());
            RedisCacheEvictOperation operation =
                    evictOperationFactory.create(method, cacheEvict, target, args, key);

            Class<?> targetClass = target != null ? target.getClass() : null;
            redisCacheRegister.registerCacheEvictOperation(method, targetClass, operation);
            log.debug(
                    "Registered cache evict operation from @RedisCaching: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register cache evict operation from @RedisCaching", e);
            return null;
        }
    }

    private RedisCachePutOperation registerCachePutOperation(
            Method method, Object target, Object[] args, RedisCachePut cachePut) {
        try {
            String key = generateKey(target, method, args, cachePut.key());
            RedisCachePutOperation operation =
                    cachePutOperationFactory.create(method, cachePut, target, args, key);

            Class<?> targetClass = target != null ? target.getClass() : null;
            redisCacheRegister.registerCachePutOperation(method, targetClass, operation);
            log.debug(
                    "Registered cache put operation from @RedisCaching: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register cache put operation from @RedisCaching", e);
            return null;
        }
    }

    private String generateKey(Object target, Method method, Object[] args, String keyExpression) {
        if (StringUtils.hasText(keyExpression)) {
            return keyExpression;
        }
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }
}
