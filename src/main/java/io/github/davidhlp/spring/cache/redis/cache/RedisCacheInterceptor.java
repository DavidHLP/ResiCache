package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.DefaultMethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.handler.AnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.EvictAnnotationHandler;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * ResiCache 缓存拦截器 —— Path C 单一 advice seam。
 *
 * <p>继承 Spring {@link CacheInterceptor} 以满足 {@code BeanFactoryCacheOperationSourceAdvisor}
 * 对 advice 的硬约束(Spring AOP 6.x 对 {@code CacheInterceptor} 子类有特殊处理:独立
 * {@code implements MethodInterceptor} 时 {@code @RedisCacheable} 装配会失效)。本类是 advisor
 * 直接持有的 advice —— 装配职责与拦截职责收口到同一处。
 *
 * <p><strong>历史收敛(本轮)</strong>:Path C 曾经历 Step 4/5/7 三次方案切换,遗留两个冗余类:
 * <ul>
 *   <li>{@code CacheAspectSupportHelper}(Step 4 产物,零引用死代码)—— 已删除</li>
 *   <li>{@code ResiCacheMethodInterceptor}(Step 5 产物,{@code invoke()} 仅 {@code return super.invoke()}
 *       的 pass-through 中间层)—— 已合并回本类</li>
 * </ul>
 * 收敛后继承面 3 层 → 2 层(本类 → {@link CacheInterceptor}),{@code cache/} 拦截器 3 类 → 1 类。
 *
 * <p>{@code invoke()} 编排(沿袭 Step 7 契约,行为零变化):
 * <ol>
 *   <li>reactive 返回类型({@code Mono}/{@code Flux})旁路 —— 不支持,直接 proceed</li>
 *   <li>{@link DefaultMethodMetadataResolver#activateStatic} ThreadLocal 激活方法元数据</li>
 *   <li>责任链 {@code handlerChain.handle} 解析注解 → 注册操作</li>
 *   <li>{@code super.invoke} 触发 {@code CacheAspectSupport.execute} —— 链增强
 *       (Bloom/Sync/TTL/NullValue/ActualCache)由 {@code RedisProCacheWriter} 在
 *       cache.get/put/evict 路径触发</li>
 *   <li>{@code finally} 清除 ThreadLocal</li>
 * </ol>
 *
 * <p>构造期注入的 {@code cacheOperationSource}/{@code cacheManager}/{@code keyGenerator} 经
 * setter 落位后调用 {@code afterPropertiesSet()}(原由 {@code ResiCacheMethodInterceptor} 转发,
 * 现收口到本类构造函数)。
 */
@Slf4j
public class RedisCacheInterceptor extends CacheInterceptor {

    private final AnnotationHandler handlerChain;

    /**
     * 构造 advice,组装注解处理责任链并落位 Spring {@link CacheInterceptor} 依赖.
     *
     * @param cacheOperationSource 缓存操作源(注解解析入口)
     * @param cacheManager         缓存管理器
     * @param keyGenerator         键生成器
     * @param cacheableHandler     {@code @RedisCacheable} 处理器(责任链头)
     * @param evictHandler         {@code @RedisCacheEvict} 处理器
     * @param cachingHandler       {@code @RedisCaching} 处理器
     * @param cachePutHandler      {@code @RedisCachePut} 处理器
     */
    public RedisCacheInterceptor(
            final CacheOperationSource cacheOperationSource,
            final CacheManager cacheManager,
            final KeyGenerator keyGenerator,
            final CacheableAnnotationHandler cacheableHandler,
            final EvictAnnotationHandler evictHandler,
            final CachingAnnotationHandler cachingHandler,
            final CachePutAnnotationHandler cachePutHandler) {
        cacheableHandler.setNext(evictHandler).setNext(cachingHandler).setNext(cachePutHandler);
        this.handlerChain = cacheableHandler;
        setCacheOperationSource(cacheOperationSource);
        setCacheManager(cacheManager);
        setKeyGenerator(keyGenerator);
        afterPropertiesSet();
        log.debug("RedisCacheInterceptor initialized as Path C advice (handler chain wired, deps injected)");
    }

    @Override
    @Nullable
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        org.springframework.util.Assert.state(target != null, "Target object must not be null");

        Object[] args = invocation.getArguments();
        Class<?> targetClass = target.getClass();

        if (isReactiveType(method.getReturnType().getName())) {
            log.warn("Reactive return type {} on [{}.{}] is not supported — bypassing cache",
                    method.getReturnType().getName(), method.getDeclaringClass().getName(), method.getName());
            return invocation.proceed();
        }

        DefaultMethodMetadataResolver.activateStatic(method, targetClass);
        try {
            handlerChain.handle(method, target, args);
            return super.invoke(invocation);
        } finally {
            DefaultMethodMetadataResolver.clearStatic();
        }
    }

    static boolean isReactiveType(String typeName) {
        return "reactor.core.publisher.Mono".equals(typeName)
                || "reactor.core.publisher.Flux".equals(typeName);
    }
}
