package io.github.davidhlp.spring.cache.redis.core.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.core.factory.EvictOperationFactory;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheEvictOperation;

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
public class EvictAnnotationHandler extends AnnotationHandler {

    private final RedisCacheRegister redisCacheRegister;
    private final KeyGenerator keyGenerator;
    private final EvictOperationFactory evictOperationFactory;

    public EvictAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            EvictOperationFactory evictOperationFactory) {
        this.redisCacheRegister = redisCacheRegister;
        this.keyGenerator = keyGenerator;
        this.evictOperationFactory = evictOperationFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return method.isAnnotationPresent(RedisCacheEvict.class);
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCacheEvict[] evicts = method.getAnnotationsByType(RedisCacheEvict.class);
        List<CacheOperation> operations = new ArrayList<>();

        for (RedisCacheEvict evict : evicts) {
            RedisCacheEvictOperation operation = registerCacheEvictOperation(method, target, args, evict);
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }

    private RedisCacheEvictOperation registerCacheEvictOperation(
            Method method, Object target, Object[] args, RedisCacheEvict cacheEvict) {
        try {
            String key = generateKey(target, method, args, cacheEvict);
            RedisCacheEvictOperation operation =
                    evictOperationFactory.create(method, cacheEvict, target, args, key);

            Class<?> targetClass = target != null ? target.getClass() : null;
            redisCacheRegister.registerCacheEvictOperation(method, targetClass, operation);
            log.debug(
                    "Registered cache evict operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register cache evict operation", e);
            return null;
        }
    }

    private String generateKey(Object target, Method method, Object[] args, RedisCacheEvict redisCacheEvict) {
        if (StringUtils.hasText(redisCacheEvict.key())) {
            return redisCacheEvict.key();
        }
        Object key = keyGenerator.generate(target, method, args);
        return String.valueOf(key);
    }
}
