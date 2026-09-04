package io.github.davidhlp.spring.cache.redis.chain.metadata;

import org.slf4j.MDC;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 方法元数据解析器.
 *
 * <p>封装"当前 AOP 拦截的缓存方法元数据"的访问,默认实现基于 private ThreadLocal。
 * 把 ThreadLocal 依赖收口到 Spring 托管的 Bean,调用点统一通过注入的 resolver,
 * 不直接依赖静态 holder。
 */
public interface MethodMetadataResolver {

    /** @return current annotated method/target key, or {@code null} outside cache interception. */
    AnnotatedElementKey currentKey();

    /** @return current intercepted method, or {@code null} outside activation. */
    Method currentMethod();

    /** @return current target class, or {@code null} outside activation. */
    Class<?> currentTargetClass();

    /** @return immutable current method context, or {@code null} when inactive. */
    MethodSnapshot currentContext();

    /**
     * Activates metadata and returns a LIFO handle that restores prior state.
     */
    ScopedActivation activate(Method method, Class<?> targetClass);

    /**
     * Captures the submitting thread's metadata before an async task is queued.
     */
    default MethodSnapshot capture() {
        return MethodSnapshot.snapshot(this);
    }

    /**
     * Restores a captured snapshot and returns the LIFO cleanup handle.
     */
    default ScopedActivation restore(MethodSnapshot snapshot) {
        return snapshot == null ? null : activate(snapshot.method(), snapshot.targetClass());
    }

    /**
     * Closes the activation returned by {@link #restore(MethodSnapshot)}.
     */
    default void clear(ScopedActivation activation) {
        if (activation != null) {
            activation.close();
        }
    }

    /**
     * Runs work with captured metadata and MDC, restoring the worker's prior
     * state in {@code finally}. The capture must happen on the submitting thread.
     */
    default <T> T runWithSnapshot(
            MethodSnapshot snapshot,
            Map<String, String> mdcSnapshot,
            Supplier<T> work) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        ScopedActivation activation = restore(snapshot);
        try {
            if (mdcSnapshot == null || mdcSnapshot.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdcSnapshot);
            }
            return work.get();
        } finally {
            try {
                clear(activation);
            } finally {
                if (previousMdc == null || previousMdc.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
            }
        }
    }

    /**
     * Metadata-only overload for callers that do not need an explicit MDC map.
     */
    default <T> T runWithSnapshot(MethodSnapshot snapshot, Supplier<T> work) {
        return runWithSnapshot(snapshot, MDC.getCopyOfContextMap(), work);
    }

    /**
     * Compatibility overload. New async callers must capture before queueing.
     */
    default <T> T runWithSnapshot(Supplier<T> work) {
        return runWithSnapshot(capture(), MDC.getCopyOfContextMap(), work);
    }
}
