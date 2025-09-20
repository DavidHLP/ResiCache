package com.david.spring.cache.redis.reflect.execution;

import java.lang.reflect.Method;

/**
 * 方法调用执行器接口
 * 策略模式：定义不同的方法调用执行策略
 */
public interface InvocationExecutor {

    /**
     * 执行方法调用
     *
     * @param targetBean 目标对象
     * @param method 目标方法
     * @param arguments 方法参数
     * @return 方法执行结果
     * @throws Exception 执行异常
     */
    Object execute(Object targetBean, Method method, Object[] arguments) throws Exception;

    /**
     * 判断是否支持执行指定的方法
     *
     * @param method 目标方法
     * @return 如果支持执行该方法返回true
     */
    default boolean supports(Method method) {
        return true;
    }

    /**
     * 获取执行器的优先级，数字越小优先级越高
     *
     * @return 优先级值
     */
    default int getOrder() {
        return 0;
    }
}