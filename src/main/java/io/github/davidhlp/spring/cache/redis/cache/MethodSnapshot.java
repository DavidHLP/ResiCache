package io.github.davidhlp.spring.cache.redis.cache;





import java.lang.reflect.Method;
import org.springframework.context.expression.AnnotatedElementKey;

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
     * Restores this snapshot and returns the activation to close in finally.
     * New callers should use {@link MethodMetadataResolver#runWithSnapshot}.
     */
    @Deprecated(since = "0.0.3", forRemoval = true)
    public ScopedActivation restore(MethodMetadataResolver resolver) {
        if (resolver == null || method == null || targetClass == null) {
            return null;
        }
        return resolver.restore(this);
    }

}
