package com.david.spring.cache.redis.reflect.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 方法调用执行器管理器
 * 管理多个执行器，根据优先级选择合适的执行器
 */
@Slf4j
@Component
public class InvocationExecutorManager {

    private final List<InvocationExecutor> executors;

    public InvocationExecutorManager(List<InvocationExecutor> executors) {
        this.executors = new ArrayList<>(executors);
        this.executors.sort(Comparator.comparingInt(InvocationExecutor::getOrder));

        // 如果没有其他执行器，添加默认执行器
        if (this.executors.isEmpty()) {
            this.executors.add(new DefaultInvocationExecutor());
        }

        log.info("Initialized {} invocation executors", this.executors.size());
    }

    /**
     * 执行方法调用
     *
     * @param targetBean 目标对象
     * @param method 目标方法
     * @param arguments 方法参数
     * @return 方法执行结果
     * @throws Exception 执行异常
     */
    public Object execute(Object targetBean, Method method, Object[] arguments) throws Exception {
        InvocationExecutor executor = selectExecutor(method);

        log.trace("Selected executor {} for method: {}#{}",
            executor.getClass().getSimpleName(),
            targetBean.getClass().getSimpleName(),
            method.getName());

        return executor.execute(targetBean, method, arguments);
    }

    /**
     * 选择合适的执行器
     *
     * @param method 目标方法
     * @return 选中的执行器
     */
    private InvocationExecutor selectExecutor(Method method) {
        for (InvocationExecutor executor : executors) {
            if (executor.supports(method)) {
                return executor;
            }
        }

        // 如果没有找到合适的执行器，使用最后一个（通常是默认执行器）
        return executors.get(executors.size() - 1);
    }

    /**
     * 获取已注册的执行器数量
     */
    public int getExecutorCount() {
        return executors.size();
    }
}