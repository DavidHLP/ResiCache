package io.github.davidhlp.spring.cache.redis.annotation;

import lombok.experimental.UtilityClass;

import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

/**
 * 注解目标对象(Method / Class)的反射多态 seam — Round 10 / ADR-0020.
 *
 * <p>本类把"在方法或类上读注解 + 提取操作名"两件散落在
 * {@link AnnotationParser} / {@link SpringAnnotationAdapter} 23 处
 * {@code instanceof Method/Class} 分派样板收口到 2 个 static helper:
 *
 * <ul>
 *   <li>{@link #findMerged(Object, Class)} — 多态读取 merged annotation,
 *       对 {@link Method} / {@link Class} 透明(两者都实现 {@link AnnotatedElement},
 *       Spring 的 {@code AnnotatedElementUtils.findMergedAnnotation(AnnotatedElement, Class)}
 *       已是多态)</li>
 *   <li>{@link #extractTargetName(Object)} — 多态提取操作名,Method 走
 *       {@code method.getName()},Class 走 {@code class.getName()},其它走
 *       {@code toString()}(保持与原 {@code target.toString()} fallback 行为一致)</li>
 * </ul>
 *
 * <p><b>为什么是 seam</b>:23 处 {@code if (target instanceof Method) ... else if (target
 * instanceof Class) ...} 重复模式,deletion test 通过(删除后 0 行为回归,Method/Class
 * 走同一多态路径);leverage 强(任何新增 {@code @RedisCache*} 注解或 Spring 兼容
 * 注解处理场景零成本接入)。
 *
 * <p><b>Java 反射类型保证</b>:
 * <ul>
 *   <li>{@code Method extends Executable extends AccessibleObject implements AnnotatedElement} ✓</li>
 *   <li>{@code Class<?> implements AnnotatedElement} ✓</li>
 * </ul>
 * 故 {@code (AnnotatedElement) target} 强转对 Method/Class 双向安全。
 *
 * <p><b>Null 容忍</b>:{@code findMerged} 对非 {@code AnnotatedElement} 的 {@code target}
 * 返回 {@code null}(与原 {@code instanceof} 不匹配时 {@code findMergedAnnotation} 不调用的
 * 行为等价);{@code extractTargetName} 对非 Method/Class 走 {@code toString()}(与原
 * {@code target.toString()} fallback 等价)。
 *
 * <p><b>本类的位置</b>:放在 {@code annotation} 包而非独立 {@code common} 包 ——
 * {@link AnnotationParser} / {@link SpringAnnotationAdapter} 是本 utility 的两个
 * 生产 consumer,utility 自身无 domain 依赖,纯反射 + Spring Core API。
 *
 * <p><b>不可变性</b>:
 * <ul>
 *   <li>{@link UtilityClass} (Lombok) 生成 private 构造 + final class 阻止实例化</li>
 *   <li>helper 全为 {@code public static},无状态,线程安全</li>
 * </ul>
 *
 * @see AnnotationParser
 * @see SpringAnnotationAdapter
 */
@UtilityClass
public final class AnnotationTargets {

    /**
     * 在方法或类上多态读取 merged annotation.
     *
     * <p>本 helper 是 Spring
     * {@link AnnotatedElementUtils#findMergedAnnotation(AnnotatedElement, Class)} 的
     * 弱类型外壳,统一接受 {@code Object} target 替代手动 {@code instanceof Method/Class}
     * 分派。对非 {@link AnnotatedElement} 的 target 返回 {@code null}。
     *
     * <p>典型用法:
     * <pre>
     * RedisCacheable ann = AnnotationTargets.findMerged(target, RedisCacheable.class);
     * if (ann != null) { ... }
     * </pre>
     *
     * @param <A>           注解类型
     * @param target        方法或类对象(可为 null,此时返回 null)
     * @param type          注解 class
     * @return merged annotation 实例(无则 null,非 AnnotatedElement 的 target 返回 null)
     */
    public static <A extends Annotation> A findMerged(final Object target, final Class<A> type) {
        if (!(target instanceof AnnotatedElement)) {
            return null;
        }
        return AnnotatedElementUtils.findMergedAnnotation((AnnotatedElement) target, type);
    }

    /**
     * 多态提取缓存操作的名称.
     *
     * <p>方法路径取 {@code method.getName()}(保留方法名作为 op 名),类路径取
     * {@code class.getName()}(全限定类名作为 op 名,与原行为一致 — 原本用
     * {@code target.toString()} 对 Class 返回的也是类全名的字符串形式,但走
     * {@code getName()} 更显式、更明确)。其它非 Method/Class 走 {@code toString()}
     * fallback(与原 else 分支 {@code target.toString()} 等价,保留行为兼容)。
     *
     * <p>典型用法:
     * <pre>
     * String name = AnnotationTargets.extractTargetName(target);
     * builder.setName(name);
     * </pre>
     *
     * @param target 方法或类对象
     * @return 操作名(Method.getName / Class.getName / target.toString 三态)
     */
    public static String extractTargetName(final Object target) {
        if (target instanceof Method) {
            return ((Method) target).getName();
        }
        if (target instanceof Class<?>) {
            return ((Class<?>) target).getName();
        }
        return target.toString();
    }
}
