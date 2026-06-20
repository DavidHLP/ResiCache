package io.github.davidhlp.spring.cache.redis.serialization;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.support.NullValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;

/**
 * Spring {@link NullValue} 的安全序列化/反序列化工具。
 *
 * <p>背景：{@link NullValue} 是 Spring 设计的 {@code final} 类（私有构造 + {@code readResolve}
 * 单例），无法被 Jackson JSON 序列化/反序列化，只能通过 Java 原生序列化往返。因此在缓存命中
 * null 值时，{@code toReturnValue} 会产出 NullValue 的 Java 序列化字节，需要由反序列化入口
 * （{@link TypeSupport} 与 {@code SecureJackson2JsonRedisSerializer}）识别并还原。
 *
 * <p><b>安全设计</b>：Java 原生反序列化是已知的 RCE 攻击面（如 CVE-2016-1000027 及各类 gadget 链）。
 * 本工具通过重写 {@link ObjectInputStream#resolveClass} 建立严格的类白名单——<b>只允许
 * {@link NullValue} 一个类</b>。反序列化流中任何其他类的引用都会被立即拒绝，从而在支持 NullValue
 * 往返的同时，彻底杜绝任意类反序列化导致的远程代码执行。{@link NullValue} 本身是 final、无字段、
 * 无自定义 {@code readObject} 逻辑的标记类，反序列化它不产生任何副作用。
 *
 * <p>相比 Java 内置的 {@code ObjectInputFilter}（JEP 290），{@code resolveClass} 白名单更直接可控、
 * 不依赖运行时 JVM 配置，且可精确匹配单个类名。
 */
@Slf4j
public final class SecureNullValueDeserializer {

    /** Java 序列化流魔数（STREAM_MAGIC=0xACED + STREAM_VERSION=0x0005），共 4 字节。 */
    private static final byte[] JAVA_SERIALIZATION_MAGIC = {(byte) 0xAC, (byte) 0xED, 0x00, 0x05};

    private SecureNullValueDeserializer() {
    }

    /**
     * 判断字节数组是否以 Java 序列化魔数（0xAC ED 00 05）开头。
     *
     * @param bytes 字节数组（可为 null）
     * @return 如果是 Java 序列化格式则返回 true
     */
    public static boolean isJavaSerialized(byte[] bytes) {
        if (bytes == null || bytes.length < JAVA_SERIALIZATION_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < JAVA_SERIALIZATION_MAGIC.length; i++) {
            if (bytes[i] != JAVA_SERIALIZATION_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将 {@link NullValue#INSTANCE} 序列化为 Java 序列化字节数组。
     *
     * <p>NullValue 因 Spring 设计（final + 私有构造）无法 JSON 化，只能用 Java 序列化，
     * 这也是 NullValue 唯一可靠的往返格式。
     *
     * @return NullValue 的 Java 序列化字节
     */
    public static byte[] serializeNullValue() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(NullValue.INSTANCE);
            oos.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize NullValue using Java serialization", e);
        }
    }

    /**
     * 安全反序列化 {@link NullValue}。
     *
     * <p>使用受限的 {@link RestrictedObjectInputStream}，其 {@code resolveClass} 仅允许
     * {@link NullValue}，任何其他类的反序列化都会被拒绝。调用方应先通过
     * {@link #isJavaSerialized(byte[])} 判定输入为 Java 序列化格式后再调用本方法。
     *
     * @param bytes Java 序列化的 NullValue 字节
     * @return 还原后的 {@link NullValue#INSTANCE}
     * @throws SecurityException 如果字节不是合法的 NullValue 序列化，或包含 NullValue 以外的类
     */
    public static NullValue deserializeNullValue(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                RestrictedObjectInputStream ois = new RestrictedObjectInputStream(bis)) {
            Object obj = ois.readObject();
            if (obj instanceof NullValue nullValue) {
                return nullValue;
            }
            throw new SecurityException(
                    "Java deserialization rejected: expected NullValue but got "
                            + (obj == null ? "null" : obj.getClass().getName()));
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException(
                    "Java deserialization rejected: only NullValue is permitted. "
                            + "Use JSON serialization for other types.",
                    e);
        }
    }

    /**
     * 受限的 {@link ObjectInputStream}，{@code resolveClass} 白名单只允许 {@link NullValue}。
     *
     * <p>反序列化流中的每一个类描述都会经过 {@link #resolveClass(ObjectStreamClass)} 校验，
     * 从机制上保证：无论流中嵌套何种 gadget，只要出现 NullValue 以外的类即被拒绝。
     */
    private static final class RestrictedObjectInputStream extends ObjectInputStream {

        RestrictedObjectInputStream(java.io.InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            if (!NullValue.class.getName().equals(desc.getName())) {
                log.warn("Java deserialization blocked by NullValue-only whitelist, rejected class: {}",
                        desc.getName());
                throw new InvalidClassException(
                        "Java deserialization is restricted to NullValue; rejected class: " + desc.getName());
            }
            return super.resolveClass(desc);
        }
    }
}
