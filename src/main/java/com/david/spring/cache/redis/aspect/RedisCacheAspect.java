package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import com.david.spring.cache.redis.register.RedisCacheRegister;
import com.david.spring.cache.redis.register.operation.RedisCacheEvictOperation;
import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RedisCacheAspect implements Ordered {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator;

    @Pointcut(
            "@annotation(com.david.spring.cache.redis.annotation.RedisCacheable) || "
                    + "@annotation(com.david.spring.cache.redis.annotation.RedisCaching)")
    public void cacheablePointcut() {}

    @Pointcut(
            "@annotation(com.david.spring.cache.redis.annotation.RedisCacheEvict) || "
                    + "@annotation(com.david.spring.cache.redis.annotation.RedisCaching)")
    public void evictPointcut() {}

    @Before("cacheablePointcut()")
    public void handleCacheableRegistration(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();

        RedisCacheable cacheable = method.getAnnotation(RedisCacheable.class);
        if (cacheable != null) {
            registerCacheableOperation(joinPoint, cacheable);
        }

        RedisCaching caching = method.getAnnotation(RedisCaching.class);
        if (caching != null) {
            for (RedisCacheable c : caching.redisCacheable()) {
                registerCacheableOperation(joinPoint, c);
            }
        }
    }

    @Before("evictPointcut()")
    public void handleEvictRegistration(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();

        RedisCacheEvict cacheEvict = method.getAnnotation(RedisCacheEvict.class);
        if (cacheEvict != null) {
            registerCacheEvictOperation(joinPoint, cacheEvict);
        }

        RedisCaching caching = method.getAnnotation(RedisCaching.class);
        if (caching != null) {
            for (RedisCacheEvict e : caching.redisCacheEvict()) {
                registerCacheEvictOperation(joinPoint, e);
            }
        }
    }

    private void registerCacheableOperation(JoinPoint joinPoint, RedisCacheable redisCacheable) {
        try {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            String key = generateKey(joinPoint);
            String[] cacheNames =
                    resolveCacheNames(redisCacheable.cacheNames(), redisCacheable.value());

            RedisCacheableOperation operation =
                    RedisCacheableOperation.builder()
                            .name(method.getName())
                            .key(key)
                            .ttl(redisCacheable.ttl())
                            .type(redisCacheable.type())
                            .useSecondLevelCache(redisCacheable.useSecondLevelCache())
                            .cacheNullValues(redisCacheable.cacheNullValues())
                            .useBloomFilter(redisCacheable.useBloomFilter())
                            .randomTtl(redisCacheable.randomTtl())
                            .variance(redisCacheable.variance())
                            .enablePreRefresh(redisCacheable.enablePreRefresh())
                            .preRefreshThreshold(redisCacheable.preRefreshThreshold())
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
                    "Registered cacheable operation: {} with key: {} for caches: {} (ttl={}s, randomTtl={}, variance={}, enablePreRefresh={}, preRefreshThreshold={})",
                    method.getName(),
                    key,
                    String.join(",", cacheNames),
                    redisCacheable.ttl(),
                    redisCacheable.randomTtl(),
                    redisCacheable.variance(),
                    redisCacheable.enablePreRefresh(),
                    redisCacheable.preRefreshThreshold());
        } catch (Exception e) {
            log.error("Failed to register cache operation", e);
        }
    }

    private void registerCacheEvictOperation(JoinPoint joinPoint, RedisCacheEvict cacheEvict) {
        try {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            String key = generateKey(joinPoint);
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
                    "Registered cache evict operation: {} with key: {} for caches: {} (allEntries={}, beforeInvocation={})",
                    method.getName(),
                    key,
                    String.join(",", cacheNames),
                    cacheEvict.allEntries(),
                    cacheEvict.beforeInvocation());
        } catch (Exception e) {
            log.error("Failed to register cache evict operation", e);
        }
    }

    private String generateKey(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object key = keyGenerator.generate(joinPoint.getTarget(), method, joinPoint.getArgs());
        return String.valueOf(key);
    }

    private String[] resolveCacheNames(String[] cacheNames, String[] values) {
        return cacheNames.length == 0 ? values : cacheNames;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
