package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.register.RedisCacheRegister;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;

import kotlinx.coroutines.Job;

import lombok.extern.slf4j.Slf4j;

import org.aopalliance.intercept.MethodInvocation;
import org.reactivestreams.Publisher;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheOperationInvoker;
import org.springframework.core.CoroutinesUtils;
import org.springframework.core.KotlinDetector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.lang.reflect.Method;

/** Redis缓存拦截器 扩展标准CacheInterceptor以支持Redis特有功能 */
@Slf4j
public class RedisCacheInterceptor extends CacheInterceptor {

    private final RedisCacheRegister redisCacheRegister;

    public RedisCacheInterceptor(RedisCacheRegister redisCacheRegister) {
        this.redisCacheRegister = redisCacheRegister;
    }

    @Override
    @Nullable
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        CacheOperationInvoker aopAllianceInvoker =
                () -> {
                    try {
                        if (KotlinDetector.isKotlinReflectPresent()
                                && KotlinDetector.isSuspendingFunction(method)) {
                            return KotlinDelegate.invokeSuspendingFunction(
                                    method, invocation.getThis(), invocation.getArguments());
                        }
                        return invocation.proceed();
                    } catch (Throwable ex) {
                        throw new CacheOperationInvoker.ThrowableWrapper(ex);
                    }
                };

        Object target = invocation.getThis();
        Assert.state(target != null, "Target must not be null");
        try {
            return execute(aopAllianceInvoker, target, method, invocation.getArguments());
        } catch (CacheOperationInvoker.ThrowableWrapper th) {
            throw th.getOriginal();
        }
    }

    /** Inner class to avoid a hard dependency on Kotlin at runtime. */
    private static class KotlinDelegate {

        public static Publisher<?> invokeSuspendingFunction(
                Method method, Object target, Object... args) {
            Continuation<?> continuation = (Continuation<?>) args[args.length - 1];
            CoroutineContext coroutineContext = continuation.getContext().minusKey(Job.Key);
            return CoroutinesUtils.invokeSuspendingFunction(coroutineContext, method, target, args);
        }
    }
}
