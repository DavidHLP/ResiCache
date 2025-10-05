package com.david.spring.cache.redis.core.writer.support.type;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/** 类型转换支持工具类 集中处理各类型转换逻辑，包括： - 字节数组与字符串的转换 - JSON序列化与反序列化 - 类型安全的类型转换 */
@Component
@RequiredArgsConstructor
public class TypeSupport {

    private final ObjectMapper objectMapper;

    /**
     * 字节数组转字符串
     *
     * @param bytes 字节数组
     * @return 字符串
     */
    @NonNull
    public String bytesToString(@NonNull byte[] bytes) {
        return new String(bytes);
    }

    /**
     * 对象序列化为字节数组
     *
     * @param value 待序列化的对象
     * @return 序列化后的字节数组，失败返回null
     */
    @Nullable
    public byte[] serializeToBytes(@NonNull Object value) {
        // 对于Spring的NullValue，使用Java序列化以保证兼容性
        if ("org.springframework.cache.support.NullValue".equals(value.getClass().getName())) {
            return serializeToJava(value);
        }

        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize value", e);
        }
    }

    /**
     * 使用Java序列化将对象序列化为字节数组
     *
     * @param value 待序列化的对象
     * @return 序列化后的字节数组
     */
    @NonNull
    private byte[] serializeToJava(@NonNull Object value) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(value);
            oos.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to serialize value using Java serialization", e);
        }
    }

    /**
     * 字节数组反序列化为对象
     *
     * @param bytes 字节数组
     * @return 反序列化后的对象，失败返回null
     */
    @Nullable
    public Object deserializeFromBytes(@NonNull byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }

        // 检测是否为Java序列化数据（以 0xAC 0xED 开头）
        if (bytes.length >= 2 && bytes[0] == (byte) 0xAC && bytes[1] == (byte) 0xED) {
            return deserializeFromJava(bytes);
        }

        // 尝试JSON反序列化
        try {
            return objectMapper.readValue(bytes, Object.class);
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize value", e);
        }
    }

    /**
     * 使用Java序列化反序列化字节数组
     *
     * @param bytes 字节数组
     * @return 反序列化后的对象
     */
    @Nullable
    private Object deserializeFromJava(@NonNull byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                ObjectInputStream ois = new ObjectInputStream(bis)) {
            Object obj = ois.readObject();
            // 如果是Spring的NullValue，返回null
            if (obj != null
                    && "org.springframework.cache.support.NullValue"
                            .equals(obj.getClass().getName())) {
                return null;
            }
            return obj;
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize Java serialized value", e);
        }
    }

    /**
     * 字节数组反序列化为指定类型对象
     *
     * @param bytes 字节数组
     * @param clazz 目标类型
     * @return 反序列化后的对象，失败返回null
     */
    @Nullable
    public <T> T deserializeFromBytes(@NonNull byte[] bytes, @NonNull Class<T> clazz) {
        try {
            return objectMapper.readValue(bytes, clazz);
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to deserialize value to type: " + clazz.getName(), e);
        }
    }

    /** 序列化异常 */
    public static class SerializationException extends RuntimeException {
        public SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
