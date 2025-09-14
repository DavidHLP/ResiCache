package com.david.spring.cache.redis.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.springframework.util.MethodInvoker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CachedInvocation {
    private Object targetBean;
    private Method targetMethod;
    private Object[] arguments;
    private CachedInvocationContext cachedInvocationContext;

    public Object invoke()
            throws ClassNotFoundException,
                    NoSuchMethodException,
                    InvocationTargetException,
                    IllegalAccessException {
        final MethodInvoker invoker = new MethodInvoker();
        invoker.setTargetObject(this.getTargetBean());
        invoker.setArguments(this.getArguments());
        invoker.setTargetMethod(this.getTargetMethod().getName());
        invoker.prepare();
        return invoker.invoke();
    }

    public record CachedInvocationContext(
            String[] cacheNames,
            String key,
            String condition,
            boolean sync,
            String[] value,
            String keyGenerator,
            String cacheManager,
            String cacheResolver,
            String unless,
            long ttl,
            Class<?> type) {}
}
