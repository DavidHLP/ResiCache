package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
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

    public RedisCacheInterceptor(RedisCacheRegister redisCacheRegister, KeyGenerator keyGenerator) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
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
            // Note: Key generation here is for registration purposes.
            // The actual key used by the cache operation might be evaluated later via SpEL.
            String key = generateKey(target, method, args);
            String[] cacheNames =
                    resolveCacheNames(redisCacheable.cacheNames(), redisCacheable.value());

            RedisCacheableOperation operation =
                    RedisCacheableOperation.builder()
                            .name(method.getName())
                            .key(key) // Storing the generated key for context
                            .ttl(redisCacheable.ttl())
                            .type(redisCacheable.type())
                            .useSecondLevelCache(redisCacheable.useSecondLevelCache())
                            .cacheNullValues(redisCacheable.cacheNullValues())
                            .useBloomFilter(redisCacheable.useBloomFilter())
                            .randomTtl(redisCacheable.randomTtl())
                            .variance(redisCacheable.variance())
                            .enablePreRefresh(redisCacheable.enablePreRefresh())
                            .preRefreshThreshold(redisCacheable.preRefreshThreshold())
                            .preRefreshMode(redisCacheable.preRefreshMode())
                            .sync(redisCacheable.sync())
                            .cacheManager(redisCacheable.cacheManager())
                            .cacheResolver(redisCacheable.cacheResolver())
                            .condition(redisCacheable.condition())
                            .keyGenerator(redisCacheable.keyGenerator())
                            .unless(redisCacheable.unless())
                            .cacheNames(cacheNames)
                            .build();

            redisCacheRegister.registerCacheableOperation(operation);
            log.debug(
                    "Registered cacheable operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", cacheNames));
        } catch (Exception e) {
            log.error("Failed to register cacheable operation", e);
        }
    }

    /** 注册 CacheEvict 缓存操作 */
    private void registerCacheEvictOperation(
            Method method, Object target, Object[] args, RedisCacheEvict cacheEvict) {
        try {
            String key = generateKey(target, method, args);
            String[] cacheNames = resolveCacheNames(cacheEvict.cacheNames(), cacheEvict.value());

            RedisCacheEvictOperation operation =
                    RedisCacheEvictOperation.builder()
                            .name(method.getName())
                            .key(key)
                            .cacheNames(cacheNames)
                            .keyGenerator(cacheEvict.keyGenerator())
                            .cacheManager(cacheEvict.cacheManager())
                            .cacheResolver(cacheEvict.cacheResolver())
                            .condition(cacheEvict.condition())
                            .allEntries(cacheEvict.allEntries())
                            .beforeInvocation(cacheEvict.beforeInvocation())
                            .sync(cacheEvict.sync())
                            .build();

            redisCacheRegister.registerCacheEvictOperation(operation);
            log.debug(
                    "Registered cache evict operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", cacheNames));
        } catch (Exception e) {
            log.error("Failed to register cache evict operation", e);
        }
    }

    private String generateKey(Object target, Method method, Object[] args) {
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }

    private String[] resolveCacheNames(String[] cacheNames, String[] values) {
        return (cacheNames != null && cacheNames.length > 0) ? cacheNames : values;
    }
}
