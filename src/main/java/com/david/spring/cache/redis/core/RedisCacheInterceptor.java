package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.core.handler.AnnotationHandler;
import com.david.spring.cache.redis.core.handler.CacheableAnnotationHandler;
import com.david.spring.cache.redis.core.handler.CachingAnnotationHandler;
import com.david.spring.cache.redis.core.handler.EvictAnnotationHandler;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.lang.reflect.Method;

/**
 * Redis缓存拦截器
 * 扩展标准CacheInterceptor以在执行标准缓存逻辑之前，注册自定义的Redis缓存操作。
 * 使用责任链模式处理不同类型的缓存注解
 */
@Slf4j
public class RedisCacheInterceptor extends CacheInterceptor {

    /** 责任链的头节点 */
    private final AnnotationHandler handlerChain;

    /**
     * 构造函数，构建注解处理器责任链
     *
     * @param cacheableHandler @RedisCacheable 注解处理器
     * @param evictHandler @RedisCacheEvict 注解处理器
     * @param cachingHandler @RedisCaching 组合注解处理器
     */
    public RedisCacheInterceptor(
            CacheableAnnotationHandler cacheableHandler,
            EvictAnnotationHandler evictHandler,
            CachingAnnotationHandler cachingHandler) {
        // 构建责任链: Cacheable -> Evict -> Caching
        cacheableHandler.setNext(evictHandler).setNext(cachingHandler);
        this.handlerChain = cacheableHandler;

        log.debug("Redis cache interceptor initialized with handler chain");
    }

    @Override
    @Nullable
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Assert.state(target != null, "Target object must not be null");

        // 1. 在执行标准缓存逻辑之前，先处理我们的自定义注解并注册操作
        handleCacheAnnotations(method, target, invocation.getArguments());

        // 2. 调用父类的invoke方法，让它处理标准的@Cacheable等注解和缓存流程
        return super.invoke(invocation);
    }

    /**
     * 使用责任链处理方法上的自定义缓存注解
     *
     * @param method 被调用的方法
     * @param target 目标对象
     * @param args 方法参数
     */
    private void handleCacheAnnotations(Method method, Object target, Object[] args) {
        handlerChain.handle(method, target, args);
    }
}
