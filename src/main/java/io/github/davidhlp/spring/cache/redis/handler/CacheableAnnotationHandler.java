package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

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
 * 处理 {@link RedisCacheable @RedisCacheable} 与 Spring {@link Cacheable @Cacheable} 注解，
 * 为其构建并注册 {@link RedisCacheableOperation}。
 *
 * <p>两条路径：
 * <ul>
 *   <li>{@code @RedisCacheable} —— 走 {@link CacheableOperationFactory}，复用
 *       {@link AbstractAnnotationHandler#registerOne} 模板；</li>
 *   <li>Spring {@code @Cacheable} —— 字段映射与 @RedisCacheable 不一致（无 TTL/布隆/早过期等增强属性），
 *       故保留独立的 {@link #registerSpringCacheableOperation} 直接走 Builder，
 *       不强行套用工厂模板，避免污染兼容路径。</li>
 * </ul>
 */
@Slf4j
@Component
public class CacheableAnnotationHandler extends AbstractAnnotationHandler {

    private final CacheableOperationFactory cacheableOperationFactory;

    public CacheableAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CacheableOperationFactory cacheableOperationFactory) {
        super(redisCacheRegister, keyGenerator);
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
            RedisCacheableOperation operation = registerOne(
                    method, target, args, cacheable, cacheable.key(),
                    cacheableOperationFactory, redisCacheRegister::registerCacheableOperation, "cacheable");
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

    /**
     * Spring {@code @Cacheable} 的字段映射：@Cacheable 无 TTL/布隆/早过期等增强属性，
     * 仅映射 Spring 原生字段（name/key/condition/unless/sync 等），直接走 Builder。
     * 异常返回 null，语义与 {@link AbstractAnnotationHandler#registerOne} 一致。
     */
    private RedisCacheableOperation registerSpringCacheableOperation(
            Method method, Object target, Object[] args, Cacheable springCacheable) {
        try {
            // key 仅用于日志；真正的运行时 key 解析由 Spring 的 CacheAspectSupport 负责。
            String key = generateKey(target, method, args, springCacheable.key());

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
