package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.factory.SpringCacheableAdapterFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 处理 {@link RedisCacheable @RedisCacheable} 与 Spring {@link Cacheable @Cacheable} 注解，
 * 为其构建并注册 {@link RedisCacheableOperation}。
 *
 * <p>两条路径都收敛为统一的 {@link AbstractAnnotationHandler#registerOne} 模板：
 * <ul>
 *   <li>{@code @RedisCacheable} —— 走 {@link CacheableOperationFactory}；</li>
 *   <li>Spring {@code @Cacheable} —— 走 {@link SpringCacheableAdapterFactory}
 *       （<strong>Candidate C</strong>：原本内联的 47 行 if-Builder 模板已抽出到该 factory）。</li>
 * </ul>
 *
 * <p>两条路径都通过同一 {@code redisCacheRegister::registerCacheableOperation} 方法引用
 * 注册，对调用方完全等价。
 */
@Slf4j
@Component
public class CacheableAnnotationHandler extends AbstractAnnotationHandler {

    private final CacheableOperationFactory cacheableOperationFactory;
    private final SpringCacheableAdapterFactory springCacheableAdapterFactory;

    public CacheableAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CacheableOperationFactory cacheableOperationFactory,
            SpringCacheableAdapterFactory springCacheableAdapterFactory) {
        super(redisCacheRegister, keyGenerator);
        this.cacheableOperationFactory = cacheableOperationFactory;
        this.springCacheableAdapterFactory = springCacheableAdapterFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheable.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class) != null;
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        List<CacheOperation> operations = new ArrayList<>();

        // @RedisCacheable 路径
        RedisCacheable cacheable = AnnotatedElementUtils.findMergedAnnotation(method, RedisCacheable.class);
        if (cacheable != null) {
            RedisCacheableOperation operation = registerOne(
                    method, target, args, cacheable, cacheable.key(),
                    cacheableOperationFactory, redisCacheRegister::registerCacheableOperation,
                    "cacheable");
            if (operation != null) {
                operations.add(operation);
            }
            return operations;
        }

        // Spring @Cacheable 路径（Candidate C 收敛后与上面对称）
        Cacheable springCacheable = AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class);
        if (springCacheable != null) {
            RedisCacheableOperation operation = registerOne(
                    method, target, args, springCacheable, springCacheable.key(),
                    springCacheableAdapterFactory, redisCacheRegister::registerCacheableOperation,
                    "spring cacheable");
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }
}
