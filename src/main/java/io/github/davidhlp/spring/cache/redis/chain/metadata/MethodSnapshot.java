package io.github.davidhlp.spring.cache.redis.chain.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

/**
 * 缓存调用上下文值对象.
 *
 * <p>不可变快照:封装一次缓存方法调用所需的全部元数据(method / targetClass /
 * annotatedElementKey)。用于 {@code RedisProCacheWriter.retrieve()/store()} 异步路径
 * 透传 ThreadLocal 状态(SDR 4 {@code supportsAsyncRetrieve()=true} 时
 * commonPool 切线程会丢 ThreadLocal,需 snapshot/restore)。
 *
 * <p>设计要点:
 * <ul>
 *   <li>不可变 record — 跨线程/跨作用域传递安全</li>
 *   <li>factory {@link #of(Method, Class)} 直接构造;{@link #of(AnnotatedElementKey)}
 *       从现有 Spring 键构造</li>
 *   <li>{@link #snapshot(MethodMetadataResolver)} / {@link #restore(MethodMetadataResolver)}
 *       供异步透传使用</li>
 * </ul>
 */
@Slf4j
public record MethodSnapshot(
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
     * 从现有 {@link AnnotatedElementKey} 构造 — 反射读 method/targetClass
     * (Spring 6.2 {@code AnnotatedElementKey} 字段为 private final 无 getter)。
     *
     * @param key Spring 的 AnnotatedElementKey,可能为 {@code null}
     * @return 上下文,key 为 null 时返回 {@code null}
     */
    public static MethodSnapshot of(AnnotatedElementKey key) {
        Method method = MetadataKeys.extractMethod(key);
        Class<?> targetClass = MetadataKeys.extractTargetClass(key);
        if (method == null || targetClass == null) {
            return null;
        }
        return new MethodSnapshot(method, targetClass, key);
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
        return of(resolver.currentKey());
    }

    /**
     * 异步透传用:restore 本快照到目标 resolver 的作用域.
     *
     * <p>走 {@link DefaultMethodMetadataResolver#restoreKey}(instance,package-private),
     * 不调静态 {@code activateStatic}。ThreadLocal 双写路径消除。
     *
     * @param resolver 目标解析器(为 {@code null} 时不操作)
     */
    public void restore(MethodMetadataResolver resolver) {
        if (method == null || targetClass == null) {
            return;
        }
        if (resolver instanceof DefaultMethodMetadataResolver dmrmr) {
            dmrmr.restoreKey(method, targetClass);
        } else if (resolver != null) {
            // Fallback:其他 resolver 实现需自己实现写入路径
            log.warn("Resolver {} is not DefaultMethodMetadataResolver — restore skipped", resolver.getClass().getName());
        }
    }
}
