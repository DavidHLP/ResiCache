package com.david.spring.cache.redis.reflect.abstracts;

import com.david.spring.cache.redis.reflect.interfaces.InvocationInterface;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.MethodInvoker;

import java.lang.reflect.Method;

@Slf4j
public abstract class AbstractInvocation implements InvocationInterface {
    protected abstract Object getTargetBean();

    protected abstract Method getTargetMethod();

    protected abstract Object[] getArguments();

    @Override
    public Object invoke() throws Exception {
        final Object target = this.getTargetBean();
        final Method method = this.getTargetMethod();
        final Object[] args = this.getArguments();

        final String targetClass = (target != null) ? target.getClass().getName() : "null";
        final String methodName = (method != null) ? method.getName() : "null";
        final int argCount = (args != null) ? args.length : 0;

        log.info("Invoking method: {}.{} with {} argument(s)", targetClass, methodName, argCount);
        if (log.isDebugEnabled()) {
            log.debug("Invocation details -> target: {}, method: {}, args: {}", target, method, args);
        }

        try {
            final MethodInvoker invoker = new MethodInvoker();
            invoker.setTargetObject(target);
            invoker.setArguments(args);
            invoker.setTargetMethod(methodName);
            invoker.prepare();

            final Object result = invoker.invoke();
            if (result == null) {
                log.info("Invocation completed: {}.{} returned null", targetClass, methodName);
            } else {
                log.info("Invocation completed: {}.{} returned type {}", targetClass, methodName,
                        result.getClass().getName());
                if (log.isDebugEnabled()) {
                    log.debug("Invocation result toString(): {}", result);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Invocation failed for {}.{} with {} argument(s)", targetClass, methodName, argCount, e);
            throw e;
        }
    }
}
