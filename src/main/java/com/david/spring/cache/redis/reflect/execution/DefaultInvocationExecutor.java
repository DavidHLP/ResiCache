package com.david.spring.cache.redis.reflect.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.MethodInvoker;

import java.lang.reflect.Method;

/**
 * 默认方法调用执行器
 * 使用Spring的MethodInvoker进行方法调用
 */
@Slf4j
public class DefaultInvocationExecutor implements InvocationExecutor {

    @Override
    public Object execute(Object targetBean, Method method, Object[] arguments) throws Exception {
        long startTime = System.currentTimeMillis();

        try {
            MethodInvoker invoker = new MethodInvoker();
            invoker.setTargetObject(targetBean);
            invoker.setArguments(arguments);
            invoker.setTargetMethod(method.getName());
            invoker.prepare();

            Object result = invoker.invoke();

            long duration = System.currentTimeMillis() - startTime;
            log.trace("Method execution completed: {}#{} in {}ms",
                targetBean.getClass().getSimpleName(), method.getName(), duration);

            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.debug("Method execution failed: {}#{} after {}ms, error: {}",
                targetBean.getClass().getSimpleName(), method.getName(), duration, e.getMessage());
            throw e;
        }
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE; // 默认执行器优先级最低
    }
}