package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import com.david.spring.cache.redis.core.factory.CacheableOperationFactory;
import com.david.spring.cache.redis.core.factory.EvictOperationFactory;
import com.david.spring.cache.redis.register.RedisCacheRegister;
import com.david.spring.cache.redis.register.operation.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.lang.reflect.Method;

/** Redis缓存拦截器 扩展标准CacheInterceptor以在执行标准缓存逻辑之前，注册自定义的Redis缓存操作。 */
@Slf4j
public class RedisCacheInterceptor extends CacheInterceptor {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator;
    private final CacheableOperationFactory cacheableOperationFactory;
    private final EvictOperationFactory evictOperationFactory;

    public RedisCacheInterceptor(
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
    @Nullable
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Assert.state(target != null, "Target object must not be null");

        // 1. 在执行标准缓存逻辑之前，先处理我们的自定义注解并注册操作
        handleCacheAnnotations(method, target, invocation.getArguments());

        // 2. 调用父类的invoke方法，让它处理标准的@Cacheable等注解和缓存流程
        return super.invoke(invocation);
    }

    /**
     * 解析方法上的自定义缓存注解，并将其注册到RedisCacheRegister中。
     *
     * @param method The method being invoked.
     * @param target The target object.
     * @param args The method arguments.
     */
    private void handleCacheAnnotations(Method method, Object target, Object[] args) {
        // 统一处理注解，包括独立注解和@RedisCaching中的注解
        RedisCacheable[] cacheables = method.getAnnotationsByType(RedisCacheable.class);
        RedisCacheEvict[] evicts = method.getAnnotationsByType(RedisCacheEvict.class);
        RedisCaching caching = method.getAnnotation(RedisCaching.class);

        for (RedisCacheable cacheable : cacheables) {
            registerCacheableOperation(method, target, args, cacheable);
        }

        for (RedisCacheEvict evict : evicts) {
            registerCacheEvictOperation(method, target, args, evict);
        }

        if (caching != null) {
            for (RedisCacheable c : caching.redisCacheable()) {
                registerCacheableOperation(method, target, args, c);
            }
            for (RedisCacheEvict e : caching.redisCacheEvict()) {
                registerCacheEvictOperation(method, target, args, e);
            }
        }
    }

    /** 注册 Cacheable 缓存操作 */
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

    /** 注册 CacheEvict 缓存操作 */
    private void registerCacheEvictOperation(
            Method method, Object target, Object[] args, RedisCacheEvict cacheEvict) {
        try {
            String key = generateKey(target, method, args);
            RedisCacheEvictOperation operation =
                    evictOperationFactory.create(method, cacheEvict, target, args, key);

            redisCacheRegister.registerCacheEvictOperation(operation);
            log.debug(
                    "Registered cache evict operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
        } catch (Exception e) {
            log.error("Failed to register cache evict operation", e);
        }
    }

    private String generateKey(Object target, Method method, Object[] args) {
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }
}
