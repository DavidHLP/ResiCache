package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.aspect.support.KeyResolver;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RedisCacheEvictAspect {

    private final EvictInvocationRegistry registry;

    public RedisCacheEvictAspect(EvictInvocationRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(redisCacheEvict)")
    public Object around(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict)
            throws Throwable {
        try {
            registerInvocation(joinPoint, redisCacheEvict);
        } catch (Exception e) {
            log.debug("Failed to register evict invocation: {}", e.getMessage());
        }
        return joinPoint.proceed();
    }

    private void registerInvocation(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict)
            throws NoSuchMethodException {

        Method method = getSpecificMethod(joinPoint);
        Object targetBean = joinPoint.getTarget();
        Object[] arguments = joinPoint.getArgs();
        String[] cacheNames = KeyResolver.getCacheNames(redisCacheEvict.value(), redisCacheEvict.cacheNames());

        boolean allEntries = redisCacheEvict.allEntries();
        Object key = null;
        if (!allEntries) {
            // 只注册主 key：若声明了 key()，优先按 SpEL 解析；否则回退到 keyGenerator
            if (redisCacheEvict.key() != null && !redisCacheEvict.key().isBlank()) {
                key = KeyResolver.resolveKeySpEL(targetBean, method, arguments, redisCacheEvict.key());
            }
            if (key == null) {
                key = resolveCacheKey(targetBean, method, arguments, redisCacheEvict);
            }
        }

        EvictInvocation invocation =
                EvictInvocation.builder()
                        .arguments(arguments)
                        .targetBean(targetBean)
                        .targetMethod(method)
                        .evictInvocationContext(
                                new EvictInvocation.EvictInvocationContext(
                                        redisCacheEvict.value(),
                                        redisCacheEvict.cacheNames(),
                                        nullToEmpty(redisCacheEvict.key()),
                                        redisCacheEvict.keyGenerator(),
                                        redisCacheEvict.cacheManager(),
                                        redisCacheEvict.cacheResolver(),
                                        nullToEmpty(redisCacheEvict.condition()),
                                        redisCacheEvict.allEntries(),
                                        redisCacheEvict.beforeInvocation(),
                                        redisCacheEvict.sync(),
                                        redisCacheEvict.keys()))
                        .build();

        for (String cacheName : cacheNames) {
            if (cacheName == null || cacheName.isBlank()) continue;
            registry.register(cacheName.trim(), key, invocation);
            log.debug(
                    "Registered EvictInvocation for cache={}, method={}, key={}, allEntries={}, beforeInvocation={}",
                    cacheName,
                    method.getName(),
                    key,
                    allEntries,
                    invocation.getEvictInvocationContext() == null
                            ? null
                            : invocation.getEvictInvocationContext().beforeInvocation());
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private Object resolveCacheKey(
            Object targetBean, Method method, Object[] arguments, RedisCacheEvict redisCacheEvict) {
        return KeyResolver.resolveKey(
                targetBean, method, arguments, redisCacheEvict.keyGenerator());
    }

    private Method getSpecificMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        Object target = joinPoint.getTarget();
        String methodName = joinPoint.getSignature().getName();
        Class<?>[] parameterTypes =
                ((MethodSignature) joinPoint.getSignature()).getMethod().getParameterTypes();
        return target.getClass().getMethod(methodName, parameterTypes);
    }
}
