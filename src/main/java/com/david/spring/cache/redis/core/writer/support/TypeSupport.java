package com.david.spring.cache.redis.core.writer.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;

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
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize value", e);
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
        try {
            return objectMapper.readValue(bytes, Object.class);
        } catch (IOException e) {
            throw new SerializationException("Failed to deserialize value", e);
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
        } catch (IOException e) {
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
