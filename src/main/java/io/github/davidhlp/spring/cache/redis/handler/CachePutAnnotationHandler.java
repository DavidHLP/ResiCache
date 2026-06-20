package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.factory.CachePutOperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class CachePutAnnotationHandler extends AnnotationHandler {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator;
    private final CachePutOperationFactory cachePutOperationFactory;

    public CachePutAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CachePutOperationFactory cachePutOperationFactory) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
        this.cachePutOperationFactory = cachePutOperationFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return method.isAnnotationPresent(RedisCachePut.class);
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCachePut[] puts = method.getAnnotationsByType(RedisCachePut.class);
        List<CacheOperation> operations = new ArrayList<>();

        for (RedisCachePut put : puts) {
            RedisCachePutOperation operation = registerCachePutOperation(method, target, args, put);
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }

    private RedisCachePutOperation registerCachePutOperation(
            Method method, Object target, Object[] args, RedisCachePut redisCachePut) {
        try {
            String key = generateKey(target, method, args, redisCachePut);
            RedisCachePutOperation operation =
                    cachePutOperationFactory.create(method, redisCachePut, target, args, key);

            Class<?> targetClass = target != null ? target.getClass() : null;
            redisCacheRegister.registerCachePutOperation(method, targetClass, operation);
            log.debug(
                    "Registered cache put operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register cache put operation", e);
            return null;
        }
    }

    private String generateKey(Object target, Method method, Object[] args, RedisCachePut redisCachePut) {
        if (StringUtils.hasText(redisCachePut.key())) {
            return redisCachePut.key();
        }
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }
}
