package io.github.davidhlp.spring.cache.redis.handler;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.annotation.RedisCaching;
import io.github.davidhlp.spring.cache.redis.factory.CachePutOperationFactory;
import io.github.davidhlp.spring.cache.redis.factory.CacheableOperationFactory;
import io.github.davidhlp.spring.cache.redis.factory.EvictOperationFactory;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 处理 {@link RedisCaching @RedisCaching} 组合注解：将其内嵌的 @RedisCacheable /
 * @RedisCacheEvict / @RedisCachePut 各自展开并注册。
 *
 * <p>三种子注解的注册均复用 {@link AbstractAnnotationHandler#registerOne} 模板，
 * 仅工厂与注册方法引用不同；样板收敛消除了原三段几乎逐字重复的 register 私有方法。
 */
@Slf4j
@Component
public class CachingAnnotationHandler extends AbstractAnnotationHandler {

    private final CacheableOperationFactory cacheableOperationFactory;
    private final EvictOperationFactory evictOperationFactory;
    private final CachePutOperationFactory cachePutOperationFactory;

    public CachingAnnotationHandler(
            RedisCacheRegister redisCacheRegister,
            KeyGenerator keyGenerator,
            CacheableOperationFactory cacheableOperationFactory,
            EvictOperationFactory evictOperationFactory,
            CachePutOperationFactory cachePutOperationFactory) {
        super(redisCacheRegister, keyGenerator);
        this.cacheableOperationFactory = cacheableOperationFactory;
        this.evictOperationFactory = evictOperationFactory;
        this.cachePutOperationFactory = cachePutOperationFactory;
    }

    @Override
    protected boolean canHandle(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class) != null;
    }

    @Override
    protected List<CacheOperation> doHandle(Method method, Object target, Object[] args) {
        RedisCaching caching = AnnotatedElementUtils.findMergedAnnotation(method, RedisCaching.class);
        List<CacheOperation> operations = new ArrayList<>();

        // 处理组合注解中的 @RedisCacheable
        for (RedisCacheable cacheable : caching.redisCacheable()) {
            RedisCacheableOperation operation = registerOne(
                    method, target, args, cacheable, cacheable.key(),
                    cacheableOperationFactory, redisCacheRegister::registerCacheableOperation, "cacheable from @RedisCaching");
            if (operation != null) {
                operations.add(operation);
            }
        }

        // 处理组合注解中的 @RedisCacheEvict
        for (RedisCacheEvict evict : caching.redisCacheEvict()) {
            RedisCacheEvictOperation operation = registerOne(
                    method, target, args, evict, evict.key(),
                    evictOperationFactory, redisCacheRegister::registerCacheEvictOperation, "cache evict from @RedisCaching");
            if (operation != null) {
                operations.add(operation);
            }
        }

        // 处理组合注解中的 @RedisCachePut
        for (RedisCachePut put : caching.redisCachePut()) {
            RedisCachePutOperation operation = registerOne(
                    method, target, args, put, put.key(),
                    cachePutOperationFactory, redisCacheRegister::registerCachePutOperation, "cache put from @RedisCaching");
            if (operation != null) {
                operations.add(operation);
            }
        }

        return operations;
    }
}
