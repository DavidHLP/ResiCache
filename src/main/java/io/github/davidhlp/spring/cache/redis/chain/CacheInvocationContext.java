package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.holder.CacheOperationMetadataHolder;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

/**
 * Path C (WS-1.3) Step 2 — 缓存调用上下文值对象.
 *
 * <p>不可变快照:封装一次缓存方法调用所需的全部元数据(method / targetClass /
 * annotatedElementKey)。用于:
 * <ul>
 *   <li>Step 6 — {@code RedisProCacheWriter.retrieve()/store()} 异步路径
 *       透传 ThreadLocal 状态(SDR 4 {@code supportsAsyncRetrieve()=true} 时
 *       commonPool 切线程会丢 ThreadLocal,需 snapshot/restore)</li>
 *   <li>Step 2+ 改用 JDK 21 {@code ScopedValue} 替代 ThreadLocal 时,本类作为
 *       {@code ScopedValue<CacheInvocationContext>} 的绑定值</li>
 * </ul>
 *
 * <p>设计要点:
 * <ul>
 *   <li>不可变 record — 跨线程/跨作用域传递安全</li>
 *   <li>factory {@link #of(Method, Class)} 直接构造;{@link #of(AnnotatedElementKey)}
 *       从现有 Spring 键构造(兼容 Step 1 的 {@code currentKey()})</li>
 *   <li>{@link #snapshot(MethodMetadataResolver)} / {@link #restore(MethodMetadataResolver)}
 *       留作 Step 6 异步透传使用,Step 2 仅声明 API 不主动调用</li>
 * </ul>
 */
public record CacheInvocationContext(
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
    public static CacheInvocationContext of(Method method, Class<?> targetClass) {
        if (method == null || targetClass == null) {
            throw new IllegalArgumentException("method and targetClass must be non-null");
        }
        return new CacheInvocationContext(method, targetClass, new AnnotatedElementKey(method, targetClass));
    }

    /**
     * 从现有 {@link AnnotatedElementKey} 构造 — 反射读 method/targetClass
     * (Spring 6.2 {@code AnnotatedElementKey} 字段为 private final 无 getter)。
     *
     * @param key Spring 的 AnnotatedElementKey,可能为 {@code null}
     * @return 上下文,key 为 null 时返回 {@code null}
     */
    public static CacheInvocationContext of(AnnotatedElementKey key) {
        if (key == null) {
            return null;
        }
        Object element = reflectField(key, "element");
        Object targetClass = reflectField(key, "targetClass");
        if (!(element instanceof Method) || !(targetClass instanceof Class<?>)) {
            return null;
        }
        return new CacheInvocationContext((Method) element, (Class<?>) targetClass, key);
    }

    /**
     * Step 6 异步透传用:snapshot 当前 resolver 状态.
     *
     * @param resolver 方法元数据解析器(可 {@code null})
     * @return 当前上下文的不可变快照,resolver 为 null 或无激活状态时返回 {@code null}
     */
    public static CacheInvocationContext snapshot(MethodMetadataResolver resolver) {
        if (resolver == null) {
            return null;
        }
        return of(resolver.currentKey());
    }

    /**
     * Step 6 异步透传用:restore 本快照到目标 resolver 的作用域.
     *
     * <p>Step 2 仅声明 API,实际调用待 Step 6 接入 {@code RedisProCacheWriter.retrieve()/store()}
     * 时实现。Step 2 临时回退到静态 {@code CacheOperationMetadataHolder} 以保证 Step 0 契约不退。
     *
     * @param resolver 目标解析器(为 {@code null} 时回退到静态 holder)
     */
    public void restore(MethodMetadataResolver resolver) {
        if (method == null || targetClass == null) {
            return;
        }
        if (resolver == null) {
            // Step 2 fallback:写静态 ThreadLocal 以维持向后兼容(Step 6 改 ScopedValue 写入)
            CacheOperationMetadataHolder.setCurrentKey(method, targetClass);
            return;
        }
        // Step 6 接入路径:ScopedValue.where(CONTEXT, this).run(...) — 留作后续 Step
        // 当前 resolver 仍是 ThreadLocal-backed,setCurrentKey 即可
        CacheOperationMetadataHolder.setCurrentKey(method, targetClass);
    }

    private static Object reflectField(AnnotatedElementKey key, String fieldName) {
        try {
            java.lang.reflect.Field f = AnnotatedElementKey.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(key);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
