package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.aspect.abstracts.AspectAbstract;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RedisCacheEvictAspect extends AspectAbstract {

    private final EvictInvocationRegistry registry;

    public RedisCacheEvictAspect(EvictInvocationRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheEvict)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        return super.around(joinPoint);
    }

    @Override
    public void registerInvocation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        Method method = getSpecificMethod(joinPoint);
        RedisCacheEvict redisCacheEvict = method.getAnnotation(RedisCacheEvict.class);
        if (redisCacheEvict == null) {
            return;
        }

        Object targetBean = joinPoint.getTarget();
        Object[] arguments = joinPoint.getArgs();
        String[] cacheNames = getCacheNames(redisCacheEvict.value(), redisCacheEvict.cacheNames());

        boolean allEntries = redisCacheEvict.allEntries();
        Object key = null;
        if (!allEntries) {
            key = resolveCacheKey(targetBean, method, arguments, redisCacheEvict.keyGenerator());
        }

        EvictInvocation invocation = EvictInvocation.builder()
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
                                redisCacheEvict.sync()))
                .build();

        for (String cacheName : cacheNames) {
            if (cacheName == null || cacheName.isBlank())
                continue;
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
}
