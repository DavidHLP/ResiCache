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

        // 检测 Reactive 返回类型并记录警告
        warnIfReactiveReturnType(method);

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

    /**
     * 检测方法返回类型是否为 Reactive 类型（Mono/Flux）。
     *
     * <p>ResiCache 目前仅支持同步缓存。若检测到 Reactive 返回类型且方法带有缓存注解，
     * 记录警告日志，提示用户当前行为将回退到 Spring 原生缓存逻辑。
     *
     * @param method 被调用的方法
     */
    private void warnIfReactiveReturnType(Method method) {
        Class<?> returnType = method.getReturnType();
        if (isReactiveType(returnType.getName())) {
            log.warn(
                    "Reactive return type {} is not fully supported by ResiCache. "
                            + "Method [{}.{}] will use Spring's native cache behavior without ResiCache enhancements.",
                    returnType.getName(),
                    method.getDeclaringClass().getName(),
                    method.getName());
        }
    }

    static boolean isReactiveType(String typeName) {
        return "reactor.core.publisher.Mono".equals(typeName)
                || "reactor.core.publisher.Flux".equals(typeName);
    }
}
