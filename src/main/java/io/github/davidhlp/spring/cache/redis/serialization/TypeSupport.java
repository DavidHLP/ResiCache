package io.github.davidhlp.spring.cache.redis.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.support.NullValue;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 类型转换支持工具类
 *
 * <p>集中处理各类型转换逻辑，包括：
 * <ul>
 *   <li>字节数组与字符串的转换</li>
 *   <li>JSON 序列化与反序列化</li>
 *   <li>NullValue 的安全序列化/反序列化（委托 {@link SecureNullValueDeserializer}）</li>
 * </ul>
 *
 * <p><b>NullValue 说明</b>：Spring 的 {@link NullValue} 是 final 类（私有构造 + readResolve 单例），
 * 无法被 Jackson JSON 化，只能通过 Java 序列化往返。本类将 NullValue 的序列化/反序列化委托给
 * {@link SecureNullValueDeserializer}，后者通过 resolveClass 白名单（仅允许 NullValue）在支持往返的
 * 同时杜绝任意类反序列化导致的 RCE。
 */
@Slf4j
@Component
public class TypeSupport {

    private final ObjectMapper objectMapper;

    public TypeSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 字节数组转字符串
     *
     * @param bytes 字节数组
     * @return 字符串
     */
    @NonNull
    public String bytesToString(@NonNull byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 对象序列化为字节数组
     *
     * <p>普通对象使用 JSON 序列化；{@link NullValue} 因 Spring 设计（final + 私有构造）只能用
     * Java 序列化往返，委托给 {@link SecureNullValueDeserializer}。
     *
     * @param value 待序列化的对象
     * @return 序列化后的字节数组，失败抛出异常
     */
    @Nullable
    public byte[] serializeToBytes(@NonNull Object value) {
        // NullValue 因 Spring 设计无法 JSON 化，使用受限 Java 序列化（白名单仅允许 NullValue）
        if (value instanceof NullValue) {
            return SecureNullValueDeserializer.serializeNullValue();
        }

        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize value", e);
        }
    }

    /**
     * 字节数组反序列化为对象
     *
     * <p>Java 序列化数据（0xAC ED 00 05 开头）仅允许 {@link NullValue}（经受限白名单反序列化），
     * 其他 Java 序列化数据一律拒绝以防 RCE；其余数据按 JSON 反序列化。
     *
     * @param bytes 字节数组
     * @return 反序列化后的对象（NullValue 或 JSON 对象），空字节返回 null
     */
    @Nullable
    public Object deserializeFromBytes(@NonNull byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }

        // 仅允许 NullValue 的受限 Java 反序列化（resolveClass 白名单杜绝 RCE），其他 Java 序列化拒绝
        if (SecureNullValueDeserializer.isJavaSerialized(bytes)) {
            return SecureNullValueDeserializer.deserializeNullValue(bytes);
        }

        // 尝试JSON反序列化
        try {
            return objectMapper.readValue(bytes, Object.class);
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize value", e);
        }
    }
}
