package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.EvictAnnotationHandler;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.lang.Nullable;

/**
 * Path C (WS-1.3) Step 7 — ResiCache 缓存方法拦截器(active advisor).
 *
 * <p><strong>Step 7 诚实记录(与 Step 3 决策偏离)</strong>:继承面未能完全消除。
 * Spring AOP {@code BeanFactoryCacheOperationSourceAdvisor} 对 {@code CacheInterceptor}
 * 子类有特殊处理(Step 5 验证:独立 {@code implements MethodInterceptor} 时
 * {@code @RedisCacheable} 3/4 测试失败,callCount=2/TTL=-2L)。本类
 * {@code extends RedisCacheInterceptor} 是必要妥协。
 *
 * <p>继承面 = 2 层(本类 → RedisCacheInterceptor → CacheInterceptor,Step 5 记录)。
 * 完整 Step 7 落地(本 tick):ThreadLocal 所有权从 {@code CacheOperationMetadataHolder}
 * 静态类迁到 {@code DefaultMethodMetadataResolver}(Spring 托管 Bean),静态 holder
 * 已删除。两类用同一 ThreadLocal 存储(Spring 单例 Bean 静态字段)。
 *
 * <p>Step 3 决策"独立 MethodInterceptor"目标在当前 Spring AOP 6.x 限制下
 * 不可达(Step 5 验证)。Path C 序列止于此妥协形态 — 继承面 = 2 层是当前
 * Spring 框架下的最小可工作解。
 */
@Slf4j
public class ResiCacheMethodInterceptor extends RedisCacheInterceptor implements MethodInterceptor {

    public ResiCacheMethodInterceptor(
            CacheOperationSource cacheOperationSource,
            CacheManager cacheManager,
            KeyGenerator keyGenerator,
            CacheableAnnotationHandler cacheableHandler,
            EvictAnnotationHandler evictHandler,
            CachingAnnotationHandler cachingHandler,
            CachePutAnnotationHandler cachePutHandler) {

        super(cacheableHandler, evictHandler, cachingHandler, cachePutHandler);
        setCacheOperationSource(cacheOperationSource);
        setCacheManager(cacheManager);
        setKeyGenerator(keyGenerator);
        afterPropertiesSet();
        log.debug("ResiCacheMethodInterceptor initialized — extends RedisCacheInterceptor (Step 7 + Step 5 decision)");
    }

    /**
     * Step 5 + Step 7:完全继承 {@link RedisCacheInterceptor#invoke} 行为
     * (Reactive bypass + ThreadLocal activate/clear via
     * {@code DefaultMethodMetadataResolver} + handlerChain.handle +
     * super.invoke 触发 {@code CacheAspectSupport.execute})。
     */
    @Override
    @Nullable
    public Object invoke(@Nullable org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
        return super.invoke(invocation);
    }
}
