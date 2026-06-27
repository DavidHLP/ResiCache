package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.handler.AnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.EvictAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.holder.CacheOperationMetadataHolder;

import lombok.extern.slf4j.Slf4j;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * Redis缓存拦截器.
 *
 * <p>扩展标准 {@link CacheInterceptor}，在 Spring 执行标准缓存逻辑之前
 * 注册自定义的 Redis 缓存操作元数据。自定义操作类已继承 Spring 的
 * 具体操作类型（{@code CacheableOperation}、{@code CachePutOperation}、
 * {@code CacheEvictOperation}），因此 {@code condition}、{@code unless}
 * 等语义由 Spring 原生处理。
 */
@Slf4j
public class RedisCacheInterceptor extends CacheInterceptor {

    private final AnnotationHandler handlerChain;

    /**
     * 构造函数，构建注解处理器责任链
     */
    public RedisCacheInterceptor(
            CacheableAnnotationHandler cacheableHandler,
            EvictAnnotationHandler evictHandler,
            CachingAnnotationHandler cachingHandler,
            CachePutAnnotationHandler cachePutHandler) {
        cacheableHandler.setNext(evictHandler).setNext(cachingHandler).setNext(cachePutHandler);
        this.handlerChain = cacheableHandler;

        log.debug("Redis cache interceptor initialized with handler chain");
    }

    @Override
    @Nullable
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        org.springframework.util.Assert.state(target != null, "Target object must not be null");

        Object[] args = invocation.getArguments();
        Class<?> targetClass = target.getClass();

        // Reactive 返回类型(Mono/Flux):ResiCache 拦截器为阻塞式,无法正确处理响应式流。
        // 自动 bypass 整个缓存路径(不注册操作、不读写缓存),避免把未订阅的 Mono/Flux
        // 当作同步值写入而污染缓存。使"缓存不会生效"与实际行为一致——既不读也不写。
        if (isReactiveType(method.getReturnType().getName())) {
            log.warn(
                    "Reactive return type {} on [{}.{}] is not supported by ResiCache — "
                            + "bypassing cache entirely (no read/write will occur). Use a "
                            + "synchronous return type or remove the cache annotation.",
                    method.getReturnType().getName(),
                    method.getDeclaringClass().getName(),
                    method.getName());
            return invocation.proceed();
        }

        // 设置当前线程的 AnnotatedElementKey，供 Writer 层进行元数据查找
        CacheOperationMetadataHolder.setCurrentKey(method, targetClass);
        try {
            // 1. 注册自定义缓存操作元数据
            //    使用责任链处理方法上的自定义注解，将操作注册到 RedisCacheRegister
            handlerChain.handle(method, target, args);

            // 2. 委托给父类的 invoke 方法
            //    由于自定义操作已继承 Spring 的具体操作类型，Spring 的
            //    CacheAspectSupport 会正确路由到 cacheable/put/evict 路径，
            //    并原生处理 condition 和 unless 语义。
            return super.invoke(invocation);
        } finally {
            CacheOperationMetadataHolder.clear();
        }
    }

    static boolean isReactiveType(String typeName) {
        return "reactor.core.publisher.Mono".equals(typeName)
                || "reactor.core.publisher.Flux".equals(typeName);
    }
}
