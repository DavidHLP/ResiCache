package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.handler.AnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.EvictAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.holder.CacheOperationMetadataHolder;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.lang.Nullable;

/**
 * Path C (WS-1.3) Step 5 — ResiCache 缓存方法拦截器(active advisor).
 *
 * <p>本 tick 实现(诚实记录 — 与 Step 3 决策部分偏离):<strong>暂时
 * {@code extends RedisCacheInterceptor}</strong>,保留老类全部行为。
 * <ul>
 *   <li>优点:切换 advisor 后行为完全等价(Step 0 契约 + 所有现有 IT 必须 0 回归)
 *   </li>
 *   <li>代价:继承面 = 2 层(本类 → RedisCacheInterceptor → CacheInterceptor),
 *       比 Step 3 决策的"1 层继承 CacheAspectSupport"略多,但不引新耦合(老类
 *       已在依赖图中,Step 7 删除时本类一起重写为不 extends)
 *   </li>
 *   <li>过渡性:Step 7 删除 {@code RedisCacheInterceptor} 时,本类重写为
 *       不 extends 任何类、直接用 {@link CacheAspectSupportHelper#execute},
 *       达成 Step 3 决策的"独立 MethodInterceptor"目标</li>
 * </ul>
 *
 * <p>本类与老 {@code RedisCacheInterceptor} 的关系:本类构造器在内部
 * 调用 {@code super(...)} 复用老类的 4 参构造(handlerChain 装配),不重复
 * 4-参构造器逻辑。
 */
@Slf4j
public class ResiCacheMethodInterceptor extends RedisCacheInterceptor {

    public ResiCacheMethodInterceptor(
            CacheOperationSource cacheOperationSource,
            CacheManager cacheManager,
            KeyGenerator keyGenerator,
            CacheableAnnotationHandler cacheableHandler,
            EvictAnnotationHandler evictHandler,
            CachingAnnotationHandler cachingHandler,
            CachePutAnnotationHandler cachePutHandler) {

        // 复用老 RedisCacheInterceptor 4-参构造(handlerChain 装配)
        super(cacheableHandler, evictHandler, cachingHandler, cachePutHandler);

        // 复用老 @Bean 的依赖装配
        setCacheOperationSource(cacheOperationSource);
        setCacheManager(cacheManager);
        setKeyGenerator(keyGenerator);
        afterPropertiesSet();
        log.debug("ResiCacheMethodInterceptor initialized — extends RedisCacheInterceptor (Step 5 transition)");
    }

    /**
     * 完全继承老 {@link RedisCacheInterceptor#invoke} 行为(Reactive bypass +
     * ThreadLocal set/clear + handlerChain.handle + super.invoke 触发
     * CacheAspectSupport.execute → cache.get/put/evict → RedisProCacheWriter 链增强)。
     *
     * <p>Step 7 删 {@code RedisCacheInterceptor} 时,本方法重写为直接调
     * {@code helper.execute(...)} 达成 Step 3"独立 MethodInterceptor"目标。
     */
    @Override
    @Nullable
    public Object invoke(@Nullable MethodInvocation invocation) throws Throwable {
        return super.invoke(invocation);
    }
}
