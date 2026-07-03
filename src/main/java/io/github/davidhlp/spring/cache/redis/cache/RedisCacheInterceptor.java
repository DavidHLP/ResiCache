package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.MethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.chain.ScopedActivation;
import io.github.davidhlp.spring.cache.redis.handler.AnnotationChainEngine;
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
 * <p><strong>历史收敛</strong>:Path C 曾经历 Step 4/5/7 三次方案切换,遗留两个冗余类:
 * <ul>
 *   <li>{@code CacheAspectSupportHelper}(Step 4 产物,零引用死代码)—— 已删除</li>
 *   <li>{@code ResiCacheMethodInterceptor}(Step 5 产物,pass-through 中间层)—— 已合并回本类</li>
 * </ul>
 * 收敛后继承面 3 层 → 2 层(本类 → {@link CacheInterceptor}),{@code cache/} 拦截器 3 类 → 1 类。
 *
 * <p><strong>链装配单一化(ADR-0013)</strong>:注解处理责任链不再在构造函数中
 * 手写 {@code setNext} 链接(原 4 行 {@code cacheableHandler.setNext(evictHandler)
 * .setNext(cachingHandler).setNext(cachePutHandler)}),改委派给
 * {@link AnnotationChainEngine}。Engine 启动期由 Spring 自动注入
 * {@code List<AnnotationHandler>},运行期无结构变更。
 *
 * <p><code>invoke()</code> 编排(行为零变化):
 * <ol>
 *   <li>reactive 返回类型({@code Mono}/{@code Flux})旁路 —— 不支持,直接 proceed</li>
 *   <li>{@link MethodMetadataResolver#activate} ThreadLocal 激活方法元数据(ADR-0036:经 activate 进入作用域)</li>
 *   <li>{@link AnnotationChainEngine#execute} 推进注解解析责任链(替代原
 *       {@code handlerChain.handle} 递归调用)</li>
 *   <li>{@code super.invoke} 触发 {@code CacheAspectSupport.execute} —— 链增强
 *       (Bloom/Sync/TTL/NullValue/ActualCache)由 {@code RedisProCacheWriter} 在
 *       cache.get/put/evict 路径触发</li>
 *   <li>{@code finally} 清除 ThreadLocal</li>
 * </ol>
 *
 * <p>构造期注入的 {@code cacheOperationSource}/{@code cacheManager}/{@code keyGenerator} 经
 * setter 落位后调用 {@code afterPropertiesSet()}。
 */
@Slf4j
public class RedisCacheInterceptor extends CacheInterceptor {

    /** 注解解析责任链推进引擎 — 替代原 AnnotationHandler 链表 + 手动 setNext 装配 */
    private final AnnotationChainEngine annotationChainEngine;

    /** 方法元数据解析器 — ADR-0035/0036:ThreadLocal 边界 owner,interceptor 经 activate() 进入作用域 */
    private final MethodMetadataResolver methodMetadataResolver;

    /**
     * 构造 advice,委派注解处理责任链装配给 {@link AnnotationChainEngine} 并落位 Spring
     * {@link CacheInterceptor} 依赖.
     *
     * @param cacheOperationSource    缓存操作源(注解解析入口)
     * @param cacheManager            缓存管理器
     * @param keyGenerator            键生成器
     * @param annotationChainEngine   注解解析责任链引擎(由 Spring 注入 List<AnnotationHandler>)
     * @param methodMetadataResolver  方法元数据解析器(ADR-0036:替代直接调 activateStatic/clearStatic)
     */
    public RedisCacheInterceptor(
            final CacheOperationSource cacheOperationSource,
            final CacheManager cacheManager,
            final KeyGenerator keyGenerator,
            final AnnotationChainEngine annotationChainEngine,
            final MethodMetadataResolver methodMetadataResolver) {
        this.annotationChainEngine = annotationChainEngine;
        this.methodMetadataResolver = methodMetadataResolver;
        setCacheOperationSource(cacheOperationSource);
        setCacheManager(cacheManager);
        setKeyGenerator(keyGenerator);
        afterPropertiesSet();
        log.debug("RedisCacheInterceptor initialized as Path C advice "
                + "(annotation chain engine wired, deps injected)");
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

        // ADR-0036:经 resolver.activate() 进入方法元数据作用域(try-with-resources 自动 restore),
        // 消除原直接调 activateStatic/clearStatic 的跨包寄生 —— 与 async 路径(writer runWithSnapshot)对称
        try (ScopedActivation ignored = methodMetadataResolver.activate(method, targetClass)) {
            annotationChainEngine.execute(method, target, args);
            return super.invoke(invocation);
        }
    }

    static boolean isReactiveType(String typeName) {
        return "reactor.core.publisher.Mono".equals(typeName)
                || "reactor.core.publisher.Flux".equals(typeName);
    }
}
