package io.github.davidhlp.spring.cache.redis.core.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.core.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler for @RedisCacheable annotations.
 *
 * <p>Registers cacheable operations with metadata lookup keys that match
 * Spring's actual cache key resolution as closely as possible.
 */
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
        return AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheable.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class) != null;
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        List<CacheOperation> operations = new ArrayList<>();

        RedisCacheable cacheable = AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheable.class);
        if (cacheable != null) {
            RedisCacheableOperation operation = registerCacheableOperation(method, target, args, cacheable);
            if (operation != null) {
                operations.add(operation);
            }
            return operations;
        }

        Cacheable springCacheable = AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class);
        if (springCacheable != null) {
            RedisCacheableOperation operation = registerSpringCacheableOperation(method, target, args, springCacheable);
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }

    private RedisCacheableOperation registerCacheableOperation(
            Method method, Object target, Object[] args, RedisCacheable redisCacheable) {
        try {
            // 不再手动解析 SpEL key 表达式；将原始表达式或 KeyGenerator 结果传给工厂。
            // 真正的 key 解析由 Spring 的 CacheAspectSupport 负责。
            String key = StringUtils.hasText(redisCacheable.key())
                    ? redisCacheable.key()
                    : String.valueOf(keyGenerator.generate(target, method, args));
            RedisCacheableOperation operation =
                    cacheableOperationFactory.create(method, redisCacheable, target, args, key);

            Class<?> targetClass = target != null ? target.getClass() : null;
            redisCacheRegister.registerCacheableOperation(method, targetClass, operation);
            log.debug(
                    "Registered cacheable operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register cacheable operation", e);
            return null;
        }
    }

    private RedisCacheableOperation registerSpringCacheableOperation(
            Method method, Object target, Object[] args, Cacheable springCacheable) {
        try {
            String key = StringUtils.hasText(springCacheable.key())
                    ? springCacheable.key()
                    : String.valueOf(keyGenerator.generate(target, method, args));

            RedisCacheableOperation.Builder builder = RedisCacheableOperation.builder();
            builder.name(method.getName());
            builder.cacheNames(
                    springCacheable.value().length > 0
                            ? springCacheable.value()
                            : springCacheable.cacheNames());
            if (StringUtils.hasText(springCacheable.key())) {
                builder.key(springCacheable.key());
            }
            if (StringUtils.hasText(springCacheable.condition())) {
                builder.condition(springCacheable.condition());
            }
            if (StringUtils.hasText(springCacheable.unless())) {
                builder.unless(springCacheable.unless());
            }
            if (StringUtils.hasText(springCacheable.keyGenerator())) {
                builder.keyGenerator(springCacheable.keyGenerator());
            }
            if (StringUtils.hasText(springCacheable.cacheManager())) {
                builder.cacheManager(springCacheable.cacheManager());
            }
            if (StringUtils.hasText(springCacheable.cacheResolver())) {
                builder.cacheResolver(springCacheable.cacheResolver());
            }
            builder.sync(springCacheable.sync());

            RedisCacheableOperation operation = builder.build();

            Class<?> targetClass = target != null ? target.getClass() : null;
            redisCacheRegister.registerCacheableOperation(method, targetClass, operation);
            log.debug(
                    "Registered Spring @Cacheable operation: {} with key: {} for caches: {}",
                    method.getName(),
                    key,
                    String.join(",", operation.getCacheNames()));
            return operation;
        } catch (Exception e) {
            log.error("Failed to register Spring @Cacheable operation", e);
            return null;
        }
    }
}
