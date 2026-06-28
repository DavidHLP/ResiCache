package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.DefaultMethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.handler.AnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.EvictAnnotationHandler;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * ResiCache 缓存拦截器(Step 7 改写:用 {@link DefaultMethodMetadataResolver} 静态 API
 * 替代已删除的 {@code CacheOperationMetadataHolder})。
 *
 * <p>Spring AOP advisor 链对 {@code CacheInterceptor} 子类有特殊处理(Step 5 验证),
 * 故保留 {@code extends CacheInterceptor} 是必要的。本类与 {@code ResiCacheMethodInterceptor}
 * 的分工:本类保留作"被继承的基类"(提供必要耦合面),{@code ResiCacheMethodInterceptor}
 * 是 advisor advice 实际持有者(Step 5 切换)。
 *
 * <p>Step 7 改写:ThreadLocal 写入/清除从 {@code CacheOperationMetadataHolder.setCurrentKey/clear}
 * 改为 {@link DefaultMethodMetadataResolver#activateStatic}/{@link DefaultMethodMetadataResolver#clearStatic}
 * (ThreadLocal 所有权已迁到 DefaultMethodMetadataResolver,本类不再直接持有)。
 */
@Slf4j
public class RedisCacheInterceptor extends CacheInterceptor {

    private final AnnotationHandler handlerChain;

    public RedisCacheInterceptor(
            CacheableAnnotationHandler cacheableHandler,
            EvictAnnotationHandler evictHandler,
            CachingAnnotationHandler cachingHandler,
            CachePutAnnotationHandler cachePutHandler) {
        cacheableHandler.setNext(evictHandler).setNext(cachingHandler).setNext(cachePutHandler);
        this.handlerChain = cacheableHandler;
        log.debug("Redis cache interceptor initialized with handler chain (Step 7: uses DefaultMethodMetadataResolver)");
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

        // Step 7:ThreadLocal 写入走 DefaultMethodMetadataResolver 静态 API
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
