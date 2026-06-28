package io.github.davidhlp.spring.cache.redis.chain;

import io.github.davidhlp.spring.cache.redis.holder.CacheOperationMetadataHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Path C (WS-1.3) — 方法元数据解析器默认实现.
 *
 * <p>Step 1 实现完全基于现有静态 {@link CacheOperationMetadataHolder} 的
 * ThreadLocal(无操作重构):读取直接代理到 {@code getCurrentKey()},激活
 * 委托给 {@code setCurrentKey()} + 返回的 {@link ScopedActivation} 在
 * {@code close()} 时调用 {@code clear()}。
 *
 * <p>数据所有权:
 * <ul>
 *   <li>Step 1: ThreadLocal 所有权在 {@code CacheOperationMetadataHolder} 静态类,
 *       本 resolver 仅为调用方门面</li>
 *   <li>Step 7: ThreadLocal 所有权迁到本 resolver,静态 holder 删除</li>
 * </ul>
 */
@Slf4j
@Component
public class DefaultMethodMetadataResolver implements MethodMetadataResolver {

    @Override
    public AnnotatedElementKey currentKey() {
        return CacheOperationMetadataHolder.getCurrentKey();
    }

    @Override
    public Method currentMethod() {
        AnnotatedElementKey key = currentKey();
        // AnnotatedElementKey 的 element/targetClass 字段是 private final,无 getter。
        // Step 1 暂用反射读 — Step 2+ 改用 ScopedValue 替代 ThreadLocal 时,会同时
        // 改造本 impl 持有 Method/Class 直接引用,不再依赖 AnnotatedElementKey 内部状态。
        if (key == null) {
            return null;
        }
        Object element = reflectField(key, "element");
        return element instanceof Method ? (Method) element : null;
    }

    @Override
    public Class<?> currentTargetClass() {
        AnnotatedElementKey key = currentKey();
        if (key == null) {
            return null;
        }
        Object targetClass = reflectField(key, "targetClass");
        return targetClass instanceof Class<?> ? (Class<?>) targetClass : null;
    }

    /**
     * 反射读取 {@link AnnotatedElementKey} 的 private 字段(Spring 6.2 之前无公开 getter)。
     * 缓存反射结果避免重复 lookup。
     */
    private static Object reflectField(AnnotatedElementKey key, String fieldName) {
        try {
            java.lang.reflect.Field f = AnnotatedElementKey.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(key);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("Failed to reflect field '{}' from AnnotatedElementKey — Step 2+ will replace this", fieldName, e);
            return null;
        }
    }

    @Override
    public ScopedActivation activate(Method method, Class<?> targetClass) {
        // 保存前一个状态(嵌套调用安全);null 表示"无前一个,close 时清空"
        Method previousMethod = currentMethod();
        Class<?> previousTargetClass = currentTargetClass();

        CacheOperationMetadataHolder.setCurrentKey(method, targetClass);
        log.debug("Activated method metadata: method={}, targetClass={}", method.getName(), targetClass.getName());

        return new ScopedActivation(() -> {
            if (previousMethod == null) {
                CacheOperationMetadataHolder.clear();
            } else {
                CacheOperationMetadataHolder.setCurrentKey(previousMethod, previousTargetClass);
            }
        });
    }
}
