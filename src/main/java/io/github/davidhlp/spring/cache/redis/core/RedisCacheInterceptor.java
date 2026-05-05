package io.github.davidhlp.spring.cache.redis.core;

import io.github.davidhlp.spring.cache.redis.core.evaluator.SpelConditionEvaluator;
import io.github.davidhlp.spring.cache.redis.core.handler.AnnotationHandler;
import io.github.davidhlp.spring.cache.redis.core.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.core.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.core.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.core.handler.EvictAnnotationHandler;

import lombok.extern.slf4j.Slf4j;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Redis缓存拦截器 扩展标准CacheInterceptor以在执行标准缓存逻辑之前，注册自定义的Redis缓存操作。
 * 使用责任链模式处理不同类型的缓存注解，并显式处理 condition/unless SpEL 表达式求值。
 */
@Slf4j
public class RedisCacheInterceptor extends CacheInterceptor {

    private final AnnotationHandler handlerChain;
    private final SpelConditionEvaluator conditionEvaluator = SpelConditionEvaluator.getInstance();

    /**
     * 构造函数，构建注解处理器责任链
     *
     * @param cacheableHandler @RedisCacheable 注解处理器
     * @param evictHandler @RedisCacheEvict 注解处理器
     * @param cachingHandler @RedisCaching 组合注解处理器
     * @param cachePutHandler @RedisCachePut 注解处理器
     */
    public RedisCacheInterceptor(
            CacheableAnnotationHandler cacheableHandler,
            EvictAnnotationHandler evictHandler,
            CachingAnnotationHandler cachingHandler,
            CachePutAnnotationHandler cachePutHandler) {
        this(cacheableHandler, evictHandler, cachingHandler, cachePutHandler, true);
    }

    /**
     * 构造函数，构建注解处理器责任链，支持配置 SpEL 错误处理行为
     *
     * @param cacheableHandler @RedisCacheable 注解处理器
     * @param evictHandler @RedisCacheEvict 注解处理器
     * @param cachingHandler @RedisCaching 组合注解处理器
     * @param cachePutHandler @RedisCachePut 注解处理器
     * @param failOnSpelError SpEL 运行时求值失败时是否抛出异常（默认 true）。
     *                        配置错误（语法错误）始终抛出，不受此设置影响。
     */
    public RedisCacheInterceptor(
            CacheableAnnotationHandler cacheableHandler,
            EvictAnnotationHandler evictHandler,
            CachingAnnotationHandler cachingHandler,
            CachePutAnnotationHandler cachePutHandler,
            boolean failOnSpelError) {
        // 构建责任链: Cacheable -> Evict -> Caching -> CachePut
        cacheableHandler.setNext(evictHandler).setNext(cachingHandler).setNext(cachePutHandler);
        this.handlerChain = cacheableHandler;

        // 配置 SpEL 求值失败时的行为
        conditionEvaluator.setFailOnSpelError(failOnSpelError);

        log.debug("Redis cache interceptor initialized with handler chain, failOnSpelError={}", failOnSpelError);
    }

    @Override
    @Nullable
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Assert.state(target != null, "Target object must not be null");

        Object[] args = invocation.getArguments();

        // 1. 处理自定义注解并注册操作，同时获取操作列表用于 SpEL 求值
        List<CacheOperation> operations = handleCacheAnnotations(method, target, args);

        // 2. 求值 condition 表达式——在方法执行前判断是否跳过缓存
        if (!evaluateCondition(operations, method, target, args)) {
            log.debug("Condition evaluated to false, skipping cache for method: {}", method.getName());
            return invocation.proceed();
        }

        // 3. 调用父类的invoke方法，让它处理标准的@Cacheable等注解和缓存流程
        Object result = super.invoke(invocation);

        // 4. 求值 unless 表达式——在方法执行后判断是否跳过缓存结果
        // 注意：由于@RedisCacheable的unless表达式在方法执行后求值，而此时缓存操作已经完成。
        // 如果unless为true，当前的实现会记录警告日志。
        // 实际的不缓存行为由下次调用时通过condition表达式控制。
        if (evaluateUnless(operations, method, target, args, result)) {
            log.warn("Unless evaluated to true for method: {}. "
                    + "Note: The result was already cached. "
                    + "Use condition expression to prevent caching before method execution.",
                    method.getName());
        }

        return result;
    }

    /**
     * 使用责任链处理方法上的自定义缓存注解
     *
     * @param method 被调用的方法
     * @param target 目标对象
     * @param args 方法参数
     * @return 处理过程中产生的缓存操作列表
     */
    private List<CacheOperation> handleCacheAnnotations(Method method, Object target, Object[] args) {
        return handlerChain.handle(method, target, args);
    }

    /**
     * 求值 condition SpEL 表达式
     *
     * @param operations 缓存操作列表
     * @param method 被调用的方法
     * @param target 目标对象
     * @param args 方法参数
     * @return true 表示执行缓存操作，false 表示跳过
     */
    private boolean evaluateCondition(
            List<CacheOperation> operations, Method method, Object target, Object[] args) {
        if (operations.isEmpty()) {
            return true;
        }

        for (CacheOperation operation : operations) {
            if (!conditionEvaluator.shouldProceed(operation, method, args, target)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 求值 unless SpEL 表达式
     *
     * @param operations 缓存操作列表
     * @param method 被调用的方法
     * @param target 目标对象
     * @param args 方法参数
     * @param result 方法执行结果
     * @return true 表示不缓存结果，false 表示缓存结果
     */
    private boolean evaluateUnless(
            List<CacheOperation> operations, Method method, Object target, Object[] args, @Nullable Object result) {
        if (operations.isEmpty()) {
            return false;
        }

        for (CacheOperation operation : operations) {
            if (conditionEvaluator.shouldSkipCache(operation, method, args, target, result)) {
                return true;
            }
        }
        return false;
    }
}
