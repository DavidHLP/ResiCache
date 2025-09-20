package com.david.spring.cache.redis.reflect.abstracts;

import com.david.spring.cache.redis.reflect.interfaces.InvocationInterface;

import org.springframework.util.MethodInvoker;

import java.lang.reflect.Method;

public abstract class AbstractInvocation implements InvocationInterface {
    protected abstract Object getTargetBean();

    protected abstract Method getTargetMethod();

    protected abstract Object[] getArguments();

    @Override
    public Object invoke() throws Exception {
        final MethodInvoker invoker = new MethodInvoker();
        invoker.setTargetObject(this.getTargetBean());
        invoker.setArguments(this.getArguments());
        invoker.setTargetMethod(this.getTargetMethod().getName());
        invoker.prepare();
        return invoker.invoke();
    }
}
