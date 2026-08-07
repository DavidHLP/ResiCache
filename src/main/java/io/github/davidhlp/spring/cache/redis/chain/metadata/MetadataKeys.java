package io.github.davidhlp.spring.cache.redis.chain.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * {@link AnnotatedElementKey} 反射访问 &amp; 类型安全 narrow seam.
 *
 * <p>本类吸收 {@code chain} 包内两文件共 3 处 {@code reflectField + instanceof} 样板:
 * <ul>
 *   <li>{@link DefaultMethodMetadataResolver} —— {@code currentMethod()} /
 *       {@code currentTargetClass()}</li>
 *   <li>{@link CacheInvocationContext} —— {@code of(AnnotatedElementKey)}</li>
 * </ul>
 *
 * <p>提供:
 * <ol>
 *   <li>{@link #reflectField(AnnotatedElementKey, String)} —— 反射私有字段,
 *       key 为 null 或字段缺失时返回 null(WARN 日志仅在字段缺失时记录)</li>
 *   <li>{@link #extractMethod(AnnotatedElementKey)} —— typed narrow,把反射值收窄到
 *       {@link Method}(非 Method 时返回 null)</li>
 *   <li>{@link #extractTargetClass(AnnotatedElementKey)} —— typed narrow,把反射值收窄到
 *       {@link Class}(非 Class 时返回 null)</li>
 * </ol>
 *
 * <p><b>设计要点</b>:
 * <ul>
 *   <li><b>类型保证</b>:Spring 6.2 {@code AnnotatedElementKey} 私有字段为
 *       {@code element}({@code Object},实际是 {@link Method}) +
 *       {@code targetClass}({@code Object},实际是 {@code Class<?>});helper 反射后用
 *       {@code instanceof} 窄化为目标类型</li>
 *   <li><b>Null 容忍</b>:{@code key == null} / 字段为 null / 字段类型不符 → 返回 null</li>
 *   <li><b>可见性</b>:package-private(同包 2 个 caller,无跨包泄漏需求);private 构造 +
 *       {@code final class} 阻止实例化</li>
 * </ul>
 *
 * <p><b>deletion test 通过</b>:删两文件 {@code reflectField} × 2 + 3 处 instanceof 分派后,
 * 复杂度从 N 处集中消失,不在 caller 端重现 —— 真实归并,而非搬家。
 *
 * @see DefaultMethodMetadataResolver
 * @see CacheInvocationContext
 */
@Slf4j
final class MetadataKeys {

    private MetadataKeys() {
        // 工具类,不可实例化
    }

    /**
     * 反射读取 {@link AnnotatedElementKey} 的 private 字段(Spring 6.2 之前无公开 getter)。
     *
     * <p>行为契约:
     * <ul>
     *   <li>{@code key == null} → 返回 null(不抛 NPE)</li>
     *   <li>字段不存在或访问被拒 → 返回 null + WARN 日志</li>
     *   <li>读取成功 → 返回字段当前值(可能为 null,这是 Spring 内部状态机的合法情况)</li>
     * </ul>
     *
     * @param key       Spring 的 AnnotatedElementKey,生产可 {@code null}
     * @param fieldName 字段名,如 {@code "element"} / {@code "targetClass"}
     * @return 字段值;{@code key} 为 null / 反射失败时返回 null
     */
    static Object reflectField(AnnotatedElementKey key, String fieldName) {
        if (key == null) {
            return null;
        }
        try {
            Field f = AnnotatedElementKey.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(key);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("Failed to reflect field '{}' from AnnotatedElementKey", fieldName, e);
            return null;
        }
    }

    /**
     * Typed narrow:从 {@link AnnotatedElementKey} 中提取 {@link Method} 元素。
     *
     * <p>等价于:
     * <pre>{@code
     * Object element = reflectField(key, "element");
     * return element instanceof Method ? (Method) element : null;
     * }</pre>
     *
     * @param key Spring 的 AnnotatedElementKey,生产可 {@code null}
     * @return Method 元素;{@code key} 为 null / 反射失败 / 元素非 Method 时返回 null
     */
    static Method extractMethod(AnnotatedElementKey key) {
        Object element = reflectField(key, "element");
        return element instanceof Method ? (Method) element : null;
    }

    /**
     * Typed narrow:从 {@link AnnotatedElementKey} 中提取目标 {@link Class}。
     *
     * <p>等价于:
     * <pre>{@code
     * Object targetClass = reflectField(key, "targetClass");
     * return targetClass instanceof Class<?> ? (Class<?>) targetClass : null;
     * }</pre>
     *
     * @param key Spring 的 AnnotatedElementKey,生产可 {@code null}
     * @return 目标 Class;{@code key} 为 null / 反射失败 / 元素非 Class 时返回 null
     */
    static Class<?> extractTargetClass(AnnotatedElementKey key) {
        Object targetClass = reflectField(key, "targetClass");
        return targetClass instanceof Class<?> ? (Class<?>) targetClass : null;
    }
}
