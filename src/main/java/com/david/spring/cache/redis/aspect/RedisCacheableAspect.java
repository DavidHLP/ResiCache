package com.david.spring.cache.redis.aspect;

import static cn.hutool.core.text.CharSequenceUtil.nullToEmpty;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.abstracts.AspectAbstract;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;

import lombok.SneakyThrows;
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
public class RedisCacheableAspect extends AspectAbstract {

    private final CacheInvocationRegistry registry;

    public RedisCacheableAspect(CacheInvocationRegistry registry) {
        this.registry = registry;
    }

    @SneakyThrows
    @Around("@annotation(redisCacheable)")
    public Object around(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) {
        try {
            registerCacheableInvocation(joinPoint, redisCacheable);
        } catch (Exception e) {
            handleRegistrationException(e);
        }
        return joinPoint.proceed();
    }

    @Override
    public void registerInvocation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        // 此方法由带注解参数的 around 方法调用具体实现，这里不直接使用
        throw new UnsupportedOperationException("请使用带注解参数的 around 方法");
    }

    /**
     * 注册缓存调用信息的具体实现
     */
    private void registerCacheableInvocation(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable)
            throws NoSuchMethodException {

        Method method = getSpecificMethod(joinPoint);
        Object targetBean = joinPoint.getTarget();
        Object[] arguments = joinPoint.getArgs();
        String[] cacheNames = getCacheNames(redisCacheable.value(), redisCacheable.cacheNames());

        Object key = resolveCacheKey(targetBean, method, arguments, redisCacheable.keyGenerator());

        CachedInvocation cachedInvocation = CachedInvocation.builder()
                .arguments(arguments)
                .targetBean(targetBean)
                .targetMethod(method)
                .cachedInvocationContext(
                        CachedInvocation.CachedInvocationContext.builder()
                                .value(redisCacheable.value())
                                .cacheNames(redisCacheable.cacheNames())
                                .key(nullToEmpty(redisCacheable.key()))
                                .keyGenerator(redisCacheable.keyGenerator())
                                .cacheManager(redisCacheable.cacheManager())
                                .cacheResolver(redisCacheable.cacheResolver())
                                .condition(nullToEmpty(redisCacheable.condition()))
                                .unless(nullToEmpty(redisCacheable.unless()))
                                .sync(redisCacheable.sync())
                                .ttl(redisCacheable.ttl())
                                .type(redisCacheable.type())
                                .build())
                .build();

        for (String cacheName : cacheNames) {
            if (cacheName == null || cacheName.isBlank())
                continue;
            registry.register(cacheName.trim(), key, cachedInvocation);
            log.debug(
                    "Registered CachedInvocation for cache={}, method={}, key={}",
                    cacheName,
                    method.getName(),
                    key);
        }
    }

}
