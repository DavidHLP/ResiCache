package io.github.davidhlp.spring.cache.redis.cache;

import java.lang.reflect.Method;
import org.springframework.context.expression.AnnotatedElementKey;

/** Immutable intercepted method and target, captured before crossing an async boundary. */
record MethodSnapshot(
        Method method,
        Class<?> targetClass,
        AnnotatedElementKey annotatedElementKey) {

    /**
     * 直接构造(method + targetClass → AnnotatedElementKey).
     *
     * @param method      被拦截的方法
     * @param targetClass 目标类(原始类,非代理类)
     * @return 不可变上下文
     */
    public static MethodSnapshot of(Method method, Class<?> targetClass) {
        if (method == null || targetClass == null) {
            throw new IllegalArgumentException("method and targetClass must be non-null");
        }
        return new MethodSnapshot(method, targetClass, new AnnotatedElementKey(method, targetClass));
    }

    /**
     * 异步透传用:snapshot 当前 resolver 状态.
     *
     * @param resolver 方法元数据解析器(可 {@code null})
     * @return 当前上下文的不可变快照,resolver 为 null 或无激活状态时返回 {@code null}
     */
    public static MethodSnapshot snapshot(MethodMetadataResolver resolver) {
        if (resolver == null) {
            return null;
        }
        Method method = resolver.currentMethod();
        Class<?> targetClass = resolver.currentTargetClass();
        return method == null || targetClass == null ? null : of(method, targetClass);
    }

}
