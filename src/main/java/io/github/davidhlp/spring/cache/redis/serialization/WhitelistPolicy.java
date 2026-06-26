package io.github.davidhlp.spring.cache.redis.serialization;

import java.util.List;
import java.util.Set;

/**
 * 统一的白名单判断策略对象（JSON 侧全集）。
 *
 * <p>背景：原先 {@link SecureJacksonRedisSerializer} 内部散布着三套白名单判断逻辑——
 * {@code createSecureObjectMapper} 中 {@code BasicPolymorphicTypeValidator} 的 {@code TypeMatcher}、
 * 反序列化前的 {@code validateTypeIds} 二次校验、以及 {@code ALLOWED_JAVA_UTIL_CLASSES} 集合。
 * 三处规则需要严格一致（任意一处漏放行 → 合法类反序列化被拒；任意一处误放行 → 安全漏洞），
 * 但分散在三个代码位置极易漂移。本类将『className 是否允许』这一判断收敛为单一来源。
 *
 * <p><b>设计契约</b>：
 * <ul>
 *   <li><b>不可变</b>：构造后所有字段 {@code final}，{@link List#copyOf} / {@link Set#copyOf}
 *       产出不可变集合。{@link SecureJacksonRedisSerializer} 实例被 {@code RedisTemplate} /
 *       {@code RedisCacheConfiguration} 持有，多线程并发调 {@code serialize}/{@code deserialize}，
 *       策略对象必须无状态、线程安全。</li>
 *   <li><b>仅共享 className 判断</b>：本类只提供『给定 className / Class 是否允许』的判断，
 *       不持有任何 Jackson / ObjectInputStream 运行时机制。{@code BasicPolymorphicTypeValidator}
 *       （JSON 运行时）与 {@link SecureNullValueDeserializer} 的 {@code resolveClass}
 *       （Java 序列化侧）各自调用本类做 className 判断，但实现机制不合并。</li>
 * </ul>
 *
 * <p><b>安全红线（不合并 Java 侧）</b>：JSON 侧白名单是『超集』（允许 io.github.davidhlp.*
 *   前缀 + java.lang/java.time/java.math/常用 java.util 集合），用于多态类型信息解析。
 *   Java 序列化侧（{@link SecureNullValueDeserializer}）是『仅 NullValue 子集』——任何 Java
 *   反序列化流中出现 NullValue 以外的类即拒绝。两侧机制不可共用代码路径（一个是 Jackson
 *   {@code BasicPolymorphicTypeValidator}，一个是 {@code ObjectInputStream.resolveClass}），
 *   强行合并会引入 bug 面。本类只供 JSON 侧共享判断；Java 侧仍保留独立的、更严的 NullValue-only
 *   断言，<b>绝不</b>把 Java 侧也改成全集（否则任意 Java 序列化类放行 → RCE 攻击面扩大）。
 */
public final class WhitelistPolicy {

    /**
     * 默认的允许反序列化包前缀。
     *
     * <p>{@link SecureJacksonRedisSerializer} 默认构造时使用，覆盖 {@code io.github.davidhlp.*}
     * 下的所有业务类（含 {@code io.github.davidhlp.spring.cache.redis.cache.CachedValue}）。
     */
    public static final String DEFAULT_ALLOWED_PACKAGE_PREFIX = "io.github.davidhlp";

    /**
     * JSON 侧允许的 {@code java.util} 集合类全集（逐项枚举，非前缀匹配）。
     *
     * <p>这些是 Jackson 多态反序列化中合法集合类型的完整清单。任何未列出的 {@code java.util.*}
     * 类（如 {@code java.util.concurrent.*}、各种 gadget 链相关类）一律拒绝。
     *
     * <p>清单来源：原 {@link SecureJacksonRedisSerializer} 私有常量，逐项搬迁（无增删）。
     */
    private static final Set<String> ALLOWED_JAVA_UTIL_CLASSES = Set.of(
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.Collections$EmptyList",
            "java.util.Collections$EmptyMap",
            "java.util.Collections$EmptySet",
            "java.util.Collections$SingletonList",
            "java.util.Collections$SingletonMap",
            "java.util.Collections$SingletonSet",
            "java.util.Collections$UnmodifiableRandomAccessList",
            "java.util.Collections$UnmodifiableList",
            "java.util.Collections$UnmodifiableMap",
            "java.util.Collections$UnmodifiableSet",
            "java.util.Collections$UnmodifiableSortedMap",
            "java.util.Collections$UnmodifiableSortedSet"
    );

    /** 允许的包前缀清单（不可变）。className 命中任一前缀即放行。 */
    private final List<String> allowedPackagePrefixes;

    /**
     * 构造白名单策略。
     *
     * @param allowedPackagePrefixes 允许的包前缀清单（构造后内部 {@link List#copyOf} 不可变化）
     */
    public WhitelistPolicy(List<String> allowedPackagePrefixes) {
        this.allowedPackagePrefixes = List.copyOf(allowedPackagePrefixes);
    }

    /**
     * 判断给定全限定类名是否在 JSON 反序列化白名单内。
     *
     * <p>判断规则（与原 {@code SecureJacksonRedisSerializer.isAllowedClass} 完全一致）：
     * <ol>
     *   <li>命中任一允许前缀（{@link #allowedPackagePrefixes}）→ 放行</li>
     *   <li>{@code java.lang.} 前缀 → 放行（基础类型包装、String 等）</li>
     *   <li>{@link #ALLOWED_JAVA_UTIL_CLASSES} 逐项枚举命中 → 放行（集合类）</li>
     *   <li>{@code java.time.} 前缀 → 放行（时间类型）</li>
     *   <li>{@code java.math.} 前缀 → 放行（BigDecimal/BigInteger）</li>
     *   <li>其他一律拒绝</li>
     * </ol>
     *
     * @param className 全限定类名（非 null）
     * @return 允许返回 true，拒绝返回 false
     */
    public boolean isClassNameAllowed(String className) {
        for (String prefix : allowedPackagePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return className.startsWith("java.lang.")
                || ALLOWED_JAVA_UTIL_CLASSES.contains(className)
                || className.startsWith("java.time.")
                || className.startsWith("java.math.");
    }

    /**
     * 判断给定 {@link Class} 是否在 JSON 反序列化白名单内。
     *
     * <p>委托 {@link #isClassNameAllowed(String)}，将 {@link Class#getName()} 作为 className 判断。
     * 供 {@code BasicPolymorphicTypeValidator.TypeMatcher} 使用（其入参是 {@code Class<?>}）。
     *
     * @param cls 待判断的类（null 返回 false）
     * @return 允许返回 true，拒绝返回 false
     */
    public boolean isClassAllowed(Class<?> cls) {
        if (cls == null) {
            return false;
        }
        return isClassNameAllowed(cls.getName());
    }
}
