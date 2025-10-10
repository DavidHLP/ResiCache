package io.github.davidhlp.spring.cache.redis.core.handler;

import java.lang.reflect.Method;

/** 注解处理器责任链 采用责任链模式处理不同类型的缓存注解 */
public abstract class AnnotationHandler {

    /** 责任链的下一个处理器 */
    protected AnnotationHandler next;

    /**
     * 设置责任链的下一个处理器
     *
     * @param next 下一个处理器
     * @return 下一个处理器（用于链式调用）
     */
    public AnnotationHandler setNext(AnnotationHandler next) {
        this.next = next;
        return next;
    }

    /**
     * 处理方法上的注解 如果当前处理器能处理，则处理后继续传递给下一个处理器 如果不能处理，直接传递给下一个处理器
     *
     * @param method 方法
     * @param target 目标对象
     * @param args 方法参数
     */
    public void handle(Method method, Object target, Object[] args) {
        if (canHandle(method)) {
            doHandle(method, target, args);
        }

        if (next != null) {
            next.handle(method, target, args);
        }
    }

    /**
     * 判断当前处理器是否能处理该方法
     *
     * @param method 方法
     * @return 是否能处理
     */
    protected abstract boolean canHandle(Method method);

    /**
     * 执行具体的处理逻辑
     *
     * @param method 方法
     * @param target 目标对象
     * @param args 方法参数
     */
    protected abstract void doHandle(Method method, Object target, Object[] args);
}
